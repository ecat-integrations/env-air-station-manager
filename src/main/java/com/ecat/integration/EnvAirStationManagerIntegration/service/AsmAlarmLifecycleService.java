package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmAlarmStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * ASM 报警生命周期服务（镜像 ADM AdmAlarmServiceImpl 的 extend-or-insert 单一身份模型）。
 *
 * <p><b>心跳窗模型</b>（替代旧 5min 去重）：报警身份 = {@code (uid, attrId, alarmType)}。
 * 每次规则命中调 {@link #recordTrigger}：
 * <ul>
 *   <li>DB 有该身份 ACTIVE 行 → {@code extendActive} 只推 last_breach_time（start_time 不动，不落新行）；</li>
 *   <li>无 ACTIVE 行 → INSERT 新 ACTIVE 行（start_time=首触发，end_time=null，last_breach_time=now）。</li>
 * </ul>
 * 闭单只有两个入口：sweep 过窗（{@code AsmAlarmSweepScheduler}）/ POWER 恢复（本服务恢复行落库时同步闭）。</p>
 *
 * <p><b>registry 同步</b>：DB 写成功后同步写/摘 {@link AsmAlarmRegistry} 内存徽章槽（registry 是 DB
 * 非权威镜像，两处同步变更保一致）。</p>
 *
 * <p>@Service 动态 jar 单例（@Component 静默跳过→NoSuchBeanDefinition）；DB 是真相源，写失败抛给
 * consumer 的 onFlushError 兜（不杀 worker）。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmAlarmLifecycleService {

    private final Log log = LogFactory.getLogger(getClass());

    private final AsmAlarmRecordMapper recordMapper;
    private final AsmAlarmRegistry registry;

    /**
     * 处理一次规则命中（consumer 对评估器产出的每条触发记录调用）。
     *
     * <p>普通命中：续期或新插 ACTIVE + registry 写槽。POWER 恢复记录（{@code record.isRecovery()}）：
     * 落恢复行（终态 INACTIVE，end_time=恢复时刻）+ 闭该身份 ACTIVE 行 + 摘槽——断电恢复即闭单，
     * 不等 sweep 过窗。</p>
     */
    public void recordTrigger(AsmAlarmRecord record) {
        Instant now = record.getLastBreachTime() != null ? record.getLastBreachTime() : Instant.now();
        if (record.isRecovery()) {
            recordMapper.insert(record);  // 恢复行：终态记录，直接落库
            closeActive(record, now, "断电恢复");
            return;
        }
        AsmAlarmRecord active = recordMapper.selectActive(
                record.getLogicDeviceUniqueId(), record.getAttrId(), record.getAlarmType());
        if (active != null) {
            recordMapper.extendActive(active.getId(), now);
            registry.put(new AsmAlarmRegistry.ActiveAlarm(record.getLogicDeviceUniqueId(),
                    record.getAttrId(), record.getAlarmType(), record.getRuleName(),
                    active.getStartTime(), now));
            return;
        }
        record.setStatus(AsmAlarmStatus.ACTIVE.name());
        record.setLastBreachTime(now);
        recordMapper.insert(record);
        registry.put(new AsmAlarmRegistry.ActiveAlarm(record.getLogicDeviceUniqueId(),
                record.getAttrId(), record.getAlarmType(), record.getRuleName(),
                record.getStartTime(), now));
    }

    /** 闭该身份 ACTIVE 行 + 摘 registry 槽（POWER 恢复路径；无 ACTIVE 行 no-op）。 */
    private void closeActive(AsmAlarmRecord record, Instant now, String reason) {
        AsmAlarmRecord active = recordMapper.selectActive(
                record.getLogicDeviceUniqueId(), record.getAttrId(), record.getAlarmType());
        if (active == null) {
            return;
        }
        recordMapper.closeBatch(java.util.Collections.singletonList(active.getId()), now);
        registry.remove(record.getLogicDeviceUniqueId(), record.getAttrId(), record.getAlarmType());
        log.info("[诊断调试] ASM 报警闭单（{}）: uid={} attr={} type={} 活跃段=[{} ~ {}]",
                reason, record.getLogicDeviceUniqueId(), record.getAttrId(),
                record.getAlarmType(), active.getStartTime(), now);
    }
}
