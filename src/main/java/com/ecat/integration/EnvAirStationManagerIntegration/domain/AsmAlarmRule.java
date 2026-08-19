package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * asm_alarm_rule 一行——动环报警规则（alarm_type 唯一 + setting_content JSON，契约兼容
 * env_alarm_settings：device_info: uniqueId→[attrId]、name/description/enabled/configurable、
 * configs[type=range/number/duration/setting]，另加可选 check/severity 字段）。
 *
 * <p>解析后的判定形态见 {@code rule.AsmAlarmRuleDefinition}；本实体只承载存储行。
 * 故意不继承 BaseEntity——mapper XML 用 FQCN。</p>
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmAlarmRule {

    /** 规则行主键。 */
    private Long id;
    /** 报警类型编码（动环域语义移植自 env-alarm-manager；库内唯一）。 */
    private String alarmType;
    /** 严重级别（0普通/1重要/2紧急；规则级可配，修复点4）。 */
    private String severity;
    /** 规则 JSON 原文。 */
    private String settingContent;
    /** 展示排序。 */
    private Integer sort;
    /** 行创建时刻。 */
    private Instant createdAt;
    /** 最近更新时刻。 */
    private Instant updatedAt;
}
