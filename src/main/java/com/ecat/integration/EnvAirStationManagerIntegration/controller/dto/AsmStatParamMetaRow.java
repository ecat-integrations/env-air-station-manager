package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Value;

/**
 * SDK 参数清单读模型——asm_config_stat（granularity_mask）JOIN asm_config_unit（STORAGE unit）投影。
 *
 * <p>与 ADM AdmStatParamConfigRow 差异：ASM 粒度掩码在 asm_config_stat 表（非 config_unit 列），
 * 故 SQL 双表 JOIN 取两真相源。非 BaseEntity 读模型——mapper XML 用 FQCN。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class AsmStatParamMetaRow {

    /** 站房逻辑设备 uniqueId。 */
    String logicDeviceUniqueId;

    /** logic attr id。 */
    String attrId;

    /** stat 存储单位（asm_config_unit STORAGE 行 unit；null=无量纲）。 */
    String unit;

    /** 适用粒度掩码（asm_config_stat.granularity_mask：bit0=分/bit1=5分/bit2=时）。 */
    Integer granularityMask;
}
