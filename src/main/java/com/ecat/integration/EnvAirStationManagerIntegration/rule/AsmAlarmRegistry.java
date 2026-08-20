package com.ecat.integration.EnvAirStationManagerIntegration.rule;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASM 活跃报警登记簿（内存徽章槽，镜像 ADM AdmMonitorRegistry 的 ALARM 槽模式）。
 *
 * <p><b>身份 key</b>：{@code (uid, attrId, alarmType)} 三元组（与 asm_alarm_record 活跃身份一致）。
 * 引擎每次命中写入/续期（写入即生效）；sweep 闭单 / POWER 恢复摘槽；core 重启后从 DB ACTIVE 行重建
 * （{@link #rebuildFromActive}，启动期单线程调，治重启孤儿徽章丢失）。</p>
 *
 * <p><b>非权威</b>：DB ACTIVE 行是权威（sweep 据此闭），本 registry 仅内存镜像——snapshot/SSE 读这里
 * 出设备级 alarm 徽章（零 SQL，便宜）。sweep 摘槽后的徽章消退靠下一帧或下次进页面对齐（已知滞后边界，
 * ADM 同款取舍）。</p>
 *
 * <p>@Service 非 @Component：动态 jar 只注册 @RestController/@Service 为 bean（@Component 静默跳过）。
 * ConcurrentHashMap 单例并发安全；快照读返不可变副本。</p>
 *
 * @author coffee
 */
@Slf4j
@Service
public class AsmAlarmRegistry {

    /** 身份三元组（uid, attrId, alarmType），与 DB 活跃行身份同构；@Value 生成 equals/hashCode。 */
    @Value
    static class AlarmKey {
        String uid;
        String attrId;
        String alarmType;
    }

    /** 活跃报警槽条目（徽章展示 + sweep 判闭所需最小元数据）。 */
    @Value
    public static class ActiveAlarm {
        String uid;
        String attrId;
        String alarmType;
        String ruleName;
        Instant startTime;
        Instant lastBreachTime;
    }

    private final Map<AlarmKey, ActiveAlarm> slots = new ConcurrentHashMap<>();

    /** 写/续期槽（引擎每次命中调；同身份覆盖，lastBreachTime=本次命中时刻）。 */
    public void put(ActiveAlarm alarm) {
        slots.put(new AlarmKey(alarm.getUid(), alarm.getAttrId(), alarm.getAlarmType()), alarm);
    }

    /** 摘槽（sweep 闭单 / POWER 恢复调；无槽 no-op 幂等）。 */
    public void remove(String uid, String attrId, String alarmType) {
        slots.remove(new AlarmKey(uid, attrId, alarmType));
    }

    /** 该设备当前活跃报警（snapshot 出口；不可变副本，无槽返空列表）。 */
    public List<ActiveAlarm> activeByUid(String uid) {
        List<ActiveAlarm> out = new ArrayList<>();
        for (ActiveAlarm a : slots.values()) {
            if (uid.equals(a.getUid())) {
                out.add(a);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 该设备是否持任何活跃报警（SSE 帧设备级 alarm 徽章，零 SQL）。 */
    public boolean hasActive(String uid) {
        for (ActiveAlarm a : slots.values()) {
            if (uid.equals(a.getUid())) {
                return true;
            }
        }
        return false;
    }

    /** 清空（测试重置 / 集成拆卸）。 */
    public void clear() {
        slots.clear();
    }

    /**
     * 启动重建：从 DB ACTIVE 行重建内存槽（core 重启后 registry 空、DB 可能仍有未过窗 ACTIVE 行）。
     * 启动期单线程调（integration onStart），与引擎写入无并发窗口。
     */
    public void rebuildFromActive(List<AsmAlarmRecord> activeRows) {
        if (activeRows == null) {
            return;
        }
        for (AsmAlarmRecord row : activeRows) {
            if (row.getLogicDeviceUniqueId() == null || row.getAttrId() == null
                    || row.getAlarmType() == null) {
                log.warn("[诊断调试] ASM registry 重建跳过身份不全的 ACTIVE 行: id={}", row.getId());
                continue;
            }
            put(new ActiveAlarm(row.getLogicDeviceUniqueId(), row.getAttrId(), row.getAlarmType(),
                    row.getRuleName(), row.getStartTime(),
                    row.getLastBreachTime() != null ? row.getLastBreachTime() : row.getStartTime()));
        }
        if (!activeRows.isEmpty()) {
            log.info("[诊断调试] ASM 报警 registry 从 DB ACTIVE 行重建 {} 条", activeRows.size());
        }
    }
}
