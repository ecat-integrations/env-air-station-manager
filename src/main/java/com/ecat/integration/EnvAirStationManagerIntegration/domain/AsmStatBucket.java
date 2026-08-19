package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 三级 stat 表一行（asm_stat_minute/_5min/_hour 同构）——avg-only 物化桶。
 *
 * <p>avg-only 引擎（D3）：每桶只产 avg_value + valid_count/total_count 计数，无 min/max/statuses 扩展列。
 * intervalMode 存 {@code AsmIntervalMode.code()}（int，进 PK）。三表共用本实体（结构逐列一致），
 * 目标表由 {@code AsmStatGranularity.targetTable()} 决定，不在实体内携带。</p>
 *
 * <p>故意不继承 BaseEntity（派生数据无审计字段）——mapper XML 用 FQCN。</p>
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmStatBucket {

    /** 桶标注（BACK=右沿 / FRONT=左沿，由 intervalMode 定）。 */
    private Instant dataTime;
    /** 站房逻辑设备 uniqueId。 */
    private String logicDeviceUniqueId;
    /** logic attr id。 */
    private String attrId;
    /** 区间模式码（AsmIntervalMode.code()：FRONT=1/BACK=2）。 */
    private Integer intervalMode;
    /** 桶内均值（avg-only 唯一聚合值）。 */
    private Double avgValue;
    /** 有效样本数。 */
    private Long validCount;
    /** 非空值计数。 */
    private Long totalCount;
}
