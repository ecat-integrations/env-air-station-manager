package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * asm_config_stat 一行——每 series（uid+attr）聚合配置。
 *
 * <p>seed 默认行（enabled=true / mask=7 全开 / BOTH）由 {@code AsmSeedService} insertIfAbsent 落库，
 * ON CONFLICT DO NOTHING 不覆盖人工配置。granularityMask 位定义见 {@code AsmGranularityMask}；
 * materializationMode 存 {@code AsmMaterializationMode.name()}。</p>
 *
 * <p>故意不继承 BaseEntity——mapper XML 用 FQCN。</p>
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmConfigStat {

    /** 站房逻辑设备 uniqueId。 */
    private String logicDeviceUniqueId;
    /** logic attr id。 */
    private String attrId;
    /** 物化总开关（默认 true）。 */
    private Boolean enabled;
    /** 适用粒度位掩码（bit0=分/bit1=5分/bit2=时；默认 7）。 */
    private Integer granularityMask;
    /** 物化范围 FRONT/BACK/BOTH（默认 BOTH）。 */
    private String materializationMode;
    /** 行创建时刻。 */
    private Instant createdAt;
    /** 最近更新时刻。 */
    private Instant updatedAt;
}
