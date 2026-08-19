package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * asm_alarm_record 一行——ASM 自有报警记录（D2：不写 env-data-manager 表）。
 *
 * <p>severity 来自规则配置（修复点4）；start_time=持续时间类首超限时刻或事件时刻，
 * end_time=触发评估时刻；断电恢复记录 description 含「恢复」语义（对齐原 checkAlarmPower 15）。</p>
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmAlarmRecord {

    /** 记录主键（库生成）。 */
    private Long id;
    /** 报警类型编码。 */
    private String alarmType;
    /** 规则名（setting_content.name 快照）。 */
    private String ruleName;
    /** 站房逻辑设备 uniqueId。 */
    private String logicDeviceUniqueId;
    /** 触发参数 id。 */
    private String attrId;
    /** 严重级别（0/1/2，来自规则）。 */
    private String severity;
    /** 报警起始时刻。 */
    private Instant startTime;
    /** 报警触发/恢复时刻。 */
    private Instant endTime;
    /** 人读描述。 */
    private String description;
    /** 状态（"0"=活跃，对齐原 Alarms 语义）。 */
    private String status;
    /** 机读明细 JSON（触发值/阈值/持续分钟等）。 */
    private String resultContent;
    /** 行创建时刻。 */
    private Instant createdAt;
}
