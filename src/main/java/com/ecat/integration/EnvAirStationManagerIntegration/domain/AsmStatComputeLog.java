package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * asm_stat_compute_log 一行——物化执行审计（逐粒度一行，insert-only；对齐 adm_stat_compute_log 形状）。
 *
 * <p>元数据+摘要不存每桶明细（明细在 stat 表，存了翻倍）。intervalMode 存 FRONT/BACK 名（配置快照）。</p>
 *
 * <p>故意不继承 BaseEntity（审计表 insert-only 无 created_by/updated_by）——mapper XML 用 FQCN。</p>
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmStatComputeLog {

    /** bigserial 回填 id。 */
    private Long id;
    /** 粒度（AsmStatGranularity.dbCode()：minute/5min/hour）。 */
    private String granularity;
    /** 触发源（SCHEDULE/RECONFIG/MANUAL）。 */
    private String triggerSource;
    /** 覆盖窗口起点（开闭由 interval_mode 定）。 */
    private Instant windowStart;
    /** 覆盖窗口终点。 */
    private Instant windowEnd;
    /** 本次物化区间模式（FRONT/BACK 配置快照）。 */
    private String intervalMode;
    /** 产出桶数（SUCCESS 非 null；FAILED null）。 */
    private Integer bucketCount;
    /** 执行开始时刻。 */
    private Instant startedAt;
    /** 执行完成时刻。 */
    private Instant endedAt;
    /** SUCCESS/FAILED。 */
    private String status;
    /** FAILED 异常摘要。 */
    private String error;
}
