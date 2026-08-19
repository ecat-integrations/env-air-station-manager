package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * asm_config_unit 一行——每 series（uid+attr+purpose）单位偏好窄行（D4，同 adm_config_unit 语义）。
 *
 * <p>unit 存 UnitInfo.getFullUnitString() key 形式；null=无量纲/读出口显原生。STORAGE 行由 seed
 * insertIfAbsent（unit=airstation attr 定义 nativeUnit，无单位则空串占位）。</p>
 *
 * <p>故意不继承 BaseEntity——mapper XML 用 FQCN。</p>
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmConfigUnit {

    /** 站房逻辑设备 uniqueId。 */
    private String logicDeviceUniqueId;
    /** logic attr id。 */
    private String attrId;
    /** 用途（AsmUnitPurpose.name()：STORAGE/MONITOR/HISTORY）。 */
    private String purpose;
    /** 目标单位（getFullUnitString key 形式）；null=无量纲/显原生。 */
    private String unit;
    /** 行创建人（seed=ASM，人工改=用户名）。 */
    private String createdBy;
    /** 最近修改人。 */
    private String updatedBy;
    /** 行创建时刻。 */
    private Instant createdAt;
    /** 最近更新时刻。 */
    private Instant updatedAt;
}
