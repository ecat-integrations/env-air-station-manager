package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * asm_alarm_record 一行——ASM 自有报警记录（D2：不写 env-data-manager 表）。
 *
 * <p>severity 来自规则配置（修复点4）；start_time=持续时间类首超限时刻或事件时刻。
 * 心跳窗生命周期（镜像 ADM adm_alarm）：ACTIVE 行 start_time=首触发、end_time=null、
 * last_breach_time=每次命中续期；sweep 过窗 / POWER 恢复时闭单为 INACTIVE（end_time=闭单时刻）。</p>
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
    /** 生命周期态（{@link com.ecat.integration.EnvAirStationManagerIntegration.support.AsmAlarmStatus} 名：ACTIVE/INACTIVE）。 */
    private String status;
    /** 最近一次命中续期时刻（心跳窗锚点；sweep 据此判闭）。 */
    private Instant lastBreachTime;
    /** 机读明细 JSON（触发值/阈值/持续分钟等）。 */
    private String resultContent;
    /** 是否为 POWER 恢复记录（仅评估器在内存标记，非库列——触发闭单该身份的 ACTIVE 行 + 落恢复行）。 */
    private boolean recovery;
    /** 行创建时刻。 */
    private Instant createdAt;
}
