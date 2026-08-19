package com.ecat.integration.EnvAirStationManagerIntegration.consumer;

import com.ecat.core.Bus.consumer.AbstractBatchBusConsumer;
import com.ecat.core.Bus.event.DeviceDataChangedEvent;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleDefinition;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleEvaluator;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;

import java.util.List;

/**
 * ASM 报警评估 consumer——订阅 device.data.update，过滤 {@code logicdevice_station.*} 站房设备，
 * 逐事件评估报警规则并落 {@code asm_alarm_record}（与数据管道 {@link AsmDataSampleConsumer} 分离，
 * SRP：报警评估独立 worker，规则评估异常/慢不拖累 raw 落库）。
 *
 * <p>过滤口径与数据管道同源（uid 前缀判据）；displayValue null 跳过（对齐原 handler 语义）。
 * 复用 {@link AbstractBatchBusConsumer} 基建：独占 worker + drop-oldest 反压，flush 抛错由
 * 基类转 {@link #onFlushError}（计数 + log，不杀线程）。</p>
 *
 * @author coffee
 */
public class AsmAlarmRuleConsumer extends AbstractBatchBusConsumer<DeviceDataChangedEvent> {

    /** airstation 逻辑设备 uniqueId 前缀（与 AsmDataSampleConsumer 同一判据）。 */
    private static final String STATION_UID_PREFIX = "logicdevice_station.";

    private final Log log = LogFactory.getLogger(getClass());

    private final DeviceRegistry registry;
    private final AsmAlarmRuleEvaluator evaluator;
    private final AsmAlarmRecordMapper recordMapper;
    private final AsmAlarmRuleIndex ruleIndex;
    private final AsmControlService controlService;

    public AsmAlarmRuleConsumer(String name, int capacity, int batchSize, long flushIntervalMs,
                                DeviceRegistry registry, AsmAlarmRuleEvaluator evaluator,
                                AsmAlarmRecordMapper recordMapper, AsmAlarmRuleIndex ruleIndex,
                                AsmControlService controlService) {
        super(name, capacity, batchSize, flushIntervalMs);
        this.registry = registry;
        this.evaluator = evaluator;
        this.recordMapper = recordMapper;
        this.ruleIndex = ruleIndex;
        this.controlService = controlService;
    }

    @Override
    protected void flush(List<DeviceDataChangedEvent> batch) {
        for (DeviceDataChangedEvent evt : batch) {
            DeviceBase device = registry.getDeviceByID(evt.getDeviceId());
            if (!(device instanceof LogicDevice)) {
                continue;  // 物理事件 / 孤儿事件
            }
            String uid = ((LogicDevice) device).getUniqueId();
            if (uid == null || !uid.startsWith(STATION_UID_PREFIX)) {
                continue;  // 非 airstation 站房逻辑设备（ADM 分析仪等，域规则不移植）
            }
            String displayValue = evt.getNewState() == null ? null : evt.getNewState().getDisplayValue();
            if (displayValue == null) {
                continue;
            }
            List<AsmAlarmRecord> triggered = evaluator.evaluate(uid, evt.getAttrId(), displayValue,
                    evt.getNewState().getLastUpdated());
            for (AsmAlarmRecord record : triggered) {
                recordMapper.insert(record);
                executeLinkage(uid, evt.getAttrId(), record);
            }
            // P4 type=8 等联动动作：报警触发后经统一控制服务以 LOCAL/asm-alarm 写联动设备（排风扇），
            // 联动失败只 log 不拖累报警落库（控制侧已落 FAILED 审计）
        }
    }

    /** 规则带联动 setting 且与触发记录同 alarmType → 经控制服务执行（origin=LOCAL, caller=asm-alarm）。 */
    private void executeLinkage(String uid, String attrId, AsmAlarmRecord record) {
        for (AsmAlarmRuleDefinition rule : ruleIndex.getRules(uid, attrId)) {
            if (!record.getAlarmType().equals(rule.getAlarmType())) {
                continue;
            }
            AsmAlarmRuleDefinition.Linkage linkage = rule.getLinkage();
            if (linkage == null) {
                continue;
            }
            try {
                controlService.execute(AsmControlOrigin.LOCAL, "asm-alarm",
                        linkage.getDeviceUid(), linkage.getAttrId(), linkage.getValue());
            } catch (RuntimeException e) {
                log.error("[诊断调试] 报警联动执行失败（报警已落库，联动设备写未成）: 规则="
                        + rule.getAlarmType() + " 联动=" + linkage.getDeviceUid() + "/"
                        + linkage.getAttrId() + ", 原因: " + e.getMessage());
            }
        }
    }

    /** DB 写失败兜底（不杀 worker）：计数 + 诊断 log。 */
    @Override
    protected void onFlushError(List<DeviceDataChangedEvent> batch, RuntimeException error) {
        log.error("[诊断调试] asm_alarm_record 写失败，丢弃本批（" + batch.size() + " 条）: ", error);
    }
}
