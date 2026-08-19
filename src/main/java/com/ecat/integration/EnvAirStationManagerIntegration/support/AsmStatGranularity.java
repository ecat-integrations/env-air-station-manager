package com.ecat.integration.EnvAirStationManagerIntegration.support;

import java.time.Duration;

/**
 * ASM 统计物化粒度拓扑——三级 avg-only 级联的单一路标（机制同 ADM StatGranularity，ASM 无 day 级）。
 *
 * <p>应用场景：物化引擎逐级（minute→5min→hour）时按本枚举取目标表名、源表名、桶宽。层级固定：
 * minute 聚合 raw、5min 与 hour 聚合 minute（mean-of-means 级联要求）。</p>
 *
 * @author coffee
 */
public enum AsmStatGranularity {
    /** 分钟（基础层）：目标 {@code asm_stat_minute}，源 raw {@code asm_data_sample}。 */
    MINUTE("asm_stat_minute", "asm_data_sample", Duration.ofMinutes(1), AsmGranularityMask.MINUTE, "minute"),
    /** 5 分钟：目标 {@code asm_stat_5min}，源 minute。 */
    FIVE_MIN("asm_stat_5min", "asm_stat_minute", Duration.ofMinutes(5), AsmGranularityMask.FIVE_MIN, "5min"),
    /** 小时：目标 {@code asm_stat_hour}，源 minute（非 5min，同 ADM 口径）。 */
    HOUR("asm_stat_hour", "asm_stat_minute", Duration.ofHours(1), AsmGranularityMask.HOUR, "hour");

    private final String targetTable;
    private final String sourceTable;
    private final Duration interval;
    private final int maskBit;
    private final String dbCode;

    AsmStatGranularity(String targetTable, String sourceTable, Duration interval, int maskBit, String dbCode) {
        this.targetTable = targetTable;
        this.sourceTable = sourceTable;
        this.interval = interval;
        this.maskBit = maskBit;
        this.dbCode = dbCode;
    }

    /** 目标分区父表名（物化写入 / 分区 ensure）。 */
    public String targetTable() {
        return targetTable;
    }

    /** 源表名（聚合读取：minute←raw / 5min·hour←minute）。 */
    public String sourceTable() {
        return sourceTable;
    }

    /** 桶宽 {@link Duration}（Java 侧分桶步进 / BACK 后标 +gap）。 */
    public Duration interval() {
        return interval;
    }

    /** {@code asm_config_stat.granularity_mask} 中的位（与 {@link AsmGranularityMask#isApplicable} 配套）。 */
    public int mask() {
        return maskBit;
    }

    /** compute_log granularity 列的落库串（minute/5min/hour）。 */
    public String dbCode() {
        return dbCode;
    }
}
