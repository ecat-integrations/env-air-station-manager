package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatComputeLog;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmStatComputeLogMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmGranularityMask;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmMaterializationMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGridBucketing;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASM avg-only 三级聚合引擎（D3：ASM 内新写精简引擎，不依赖 ADM jar）。
 *
 * <p>应用场景：对 window 内的 raw 样本 / minute 子桶（fetch 时间范围，非桶边界）按
 * {@link AsmStatGranularity} 分桶产出 avg-only 桶行（avg_value + valid_count/total_count），
 * 经 {@link AsmStatMapper} ON CONFLICT 4 列 PK upsert（幂等，重算/回补安全）。聚合全在 Java，
 * DB 只 SELECT WHERE 取源行（零 SQL 聚合）。</p>
 *
 * <p><b>聚合语义（avg-only 精简，无 O3-8h/PM 代表值/单位换算/有效性四态）</b>：
 * <ul>
 *   <li>minute←raw：桶均值 = 窗口内非空 value_num 算术均值；valid_count = 非空样本数、
 *       total_count = 窗口内全部样本数；全空桶（valid=0）不写行。</li>
 *   <li>5min/hour←minute 级联：均值按子桶 <b>valid_count 加权</b>（sum(avg*valid)/sum(valid)，
 *       非 mean-of-means 简单平均——子桶样本数不等时 mean-of-means 有偏）；父 valid/total =
 *       子桶计数求和；无任何有效子桶的父桶不写行。</li>
 * </ul></p>
 *
 * <p><b>门控</b>：只物化有 asm_config_stat 行且 enabled=true 的 series；granularity_mask 位未开的
 * 粒度跳过；materialization_mode（FRONT/BACK/BOTH）决定跑哪些区间模式——FRONT-only 不产 BACK 行。</p>
 *
 * <p><b>mode 依赖 WHERE + 桶标</b>（单一真相源 {@link AsmIntervalMode} / {@link AsmStatGridBucketing}）：
 * FRONT [S,E) 桶标=左沿、WHERE {@code >= ? and < ?}；BACK (L,R] 桶标=右沿、WHERE {@code > ? and <= ?}。
 * 子桶 fetch 恒带 {@code interval_mode = code} 过滤（级联 mode 自洽：FRONT 子桶→FRONT 父桶）。</p>
 *
 * <p><b>计算审计</b>：每 (粒度, mode) 物化写一行 asm_stat_compute_log（SUCCESS 记 bucketCount /
 * FAILED 记 error 后上抛）；单 series 失败隔离跳过（其余 series 继续），全 series 失败才整体 FAILED+上抛。
 * 时间源注入（{@code now} 参数）——调度/测试传同一 Instant 确定性落 started/ended，无隐式时钟。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmStatAggregationEngine {

    /** fetch 扩窗宽度：每端外扩 1 个该粒度桶（覆盖首/末桶被窗口边界部分覆盖的情形；upsert 幂等多覆盖无害）。 */
    private static final int FETCH_PAD_BUCKETS = 1;

    private final Log log = LogFactory.getLogger(getClass());

    private final JdbcTemplate jdbcTemplate;
    private final AsmConfigStatMapper configStatMapper;
    private final AsmStatMapper statMapper;
    private final AsmStatComputeLogMapper computeLogMapper;
    private final AsmStatPartitionManager partitionManager;

    /**
     * 单粒度物化入口（调度器 / 手动重算共用）：加载 asm_config_stat 全行，按 enabled + mask 过滤，
     * 按各 series 的 materialization_mode 分组，对每个 (粒度, mode) 跑一趟「fetch→分桶→聚合→upsert」。
     *
     * @param granularity  物化粒度（MINUTE←raw / FIVE_MIN·HOUR←minute）
     * @param windowStart  原始数据范围左端（UTC；fetch 自动左扩 1 桶；开闭由 mode 定）
     * @param windowEnd    原始数据范围右端（UTC；fetch 自动右扩 1 桶）
     * @param triggerSource 触发源（compute_log.trigger_source：SCHEDULE/RECONFIG/MANUAL）
     * @param now          时间源（审计 started/ended 落此值；调用方注入保证确定性）
     * @return 各 mode 物化产出桶行数之和
     */
    public int materializeGranularity(AsmStatGranularity granularity, Instant windowStart, Instant windowEnd,
                                      String triggerSource, Instant now) {
        if (windowStart == null || windowEnd == null || now == null) {
            throw new IllegalArgumentException(
                    "materializeGranularity 入参不可为 null: ws=" + windowStart + " we=" + windowEnd + " now=" + now);
        }
        // 网格对齐（bug-record-20260818-225500 方案 A）：调度 tick 时刻带相位（边界+delay 秒），
        // ws/we 未对齐时 fetch pad 锚在相位 ws 上 → 重算边界 minute 桶时截掉最早成员行并覆写回缩。
        // 入口处按粒度 floor（FRONT 语义）对齐后，重算窗恒为完整网格桶集合，pad 退化为纯保险。
        // 对齐放 engine（而非 scheduler）使所有调用方统一受益（调度/手动 recompute/回补）。
        Instant alignedStart = AsmStatGridBucketing.truncateToGrid(windowStart, granularity, AsmIntervalMode.FRONT);
        Instant alignedEnd = AsmStatGridBucketing.truncateToGrid(windowEnd, granularity, AsmIntervalMode.FRONT);
        List<AsmConfigStat> configs = configStatMapper.selectAll();
        if (configs == null || configs.isEmpty()) {
            log.info("[诊断调试] ASM 物化跳过（无 config_stat 行，配置未 seed）: gran={} trigger={}",
                    granularity.dbCode(), triggerSource);
            return 0;
        }
        // 过滤（enabled + mask）并按 mode 分组——mode 遍历序恒 EnumSet 自然序（FRONT 先 BACK 后），审计可复现
        Map<AsmIntervalMode, List<AsmConfigStat>> byMode = new EnumMap<>(AsmIntervalMode.class);
        for (AsmConfigStat cfg : configs) {
            if (cfg.getEnabled() == null || !cfg.getEnabled()) {
                continue;
            }
            if (!AsmGranularityMask.isApplicable(AsmGranularityMask.validate(cfg.getGranularityMask()), granularity)) {
                continue;
            }
            AsmMaterializationMode scope = AsmMaterializationMode.of(cfg.getMaterializationMode());
            for (AsmIntervalMode mode : scope.modes()) {
                byMode.computeIfAbsent(mode, k -> new ArrayList<>()).add(cfg);
            }
        }
        int total = 0;
        for (Map.Entry<AsmIntervalMode, List<AsmConfigStat>> e : byMode.entrySet()) {
            total += materializeForMode(granularity, e.getKey(), e.getValue(), alignedStart, alignedEnd, triggerSource, now);
        }
        return total;
    }

    /** 单 (粒度, mode) 一趟：分区 ensure → 逐 series 物化（per-series 隔离）→ SUCCESS/FAILED 审计。 */
    private int materializeForMode(AsmStatGranularity gran, AsmIntervalMode mode, List<AsmConfigStat> series,
                                   Instant ws, Instant we, String trigger, Instant now) {
        Duration pad = gran.interval().multipliedBy(FETCH_PAD_BUCKETS);
        partitionManager.ensureStatPartitions(ws.minus(pad), we.plus(pad));
        int bucketCount = 0;
        int failedSeries = 0;
        RuntimeException firstFailure = null;
        for (AsmConfigStat cfg : series) {
            try {
                bucketCount += materializeOneSeries(gran, mode, cfg, ws, we);
            } catch (RuntimeException e) {
                // per-series 隔离：单 series 异常（数据/配置形态问题）不杀同粒度其余 series；
                // 失败 series 缺口由修复后下个 tick 补（upsert 幂等）。全灭才整体 FAILED+上抛。
                failedSeries++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                log.error("[诊断调试] ASM 单 series 物化失败，已隔离跳过: gran={} mode={} series={}:{} => {}: ",
                        new Object[]{gran.dbCode(), mode, cfg.getLogicDeviceUniqueId(), cfg.getAttrId(),
                                e.getMessage(), e});
            }
        }
        if (failedSeries > 0 && failedSeries == series.size()) {
            writeAudit(gran, mode, trigger, ws, we, "FAILED", null, now, firstFailure.getMessage());
            throw firstFailure;
        }
        writeAudit(gran, mode, trigger, ws, we, "SUCCESS", bucketCount, now, null);
        log.info("[诊断调试] ASM 物化完成: gran={} mode={} trigger={} series={} buckets={} failedSeries={}",
                new Object[]{gran.dbCode(), mode, trigger, series.size(), bucketCount, failedSeries});
        return bucketCount;
    }

    /** 单 series 一轮：fetch（扩窗 1 桶）→ Java 分桶聚合 → upsert。空产出（无源行/全空桶）返 0。 */
    private int materializeOneSeries(AsmStatGranularity gran, AsmIntervalMode mode,
                                     AsmConfigStat cfg, Instant ws, Instant we) {
        Duration pad = gran.interval().multipliedBy(FETCH_PAD_BUCKETS);
        Instant fetchStart = ws.minus(pad);
        Instant fetchEnd = we.plus(pad);
        List<AsmStatBucket> buckets;
        if (gran == AsmStatGranularity.MINUTE) {
            buckets = aggregateMinute(
                    fetchRawSamples(mode, fetchStart, fetchEnd, cfg.getLogicDeviceUniqueId(), cfg.getAttrId()),
                    mode, cfg);
        } else {
            buckets = aggregateCascade(
                    fetchChildBuckets(gran, mode, fetchStart, fetchEnd, cfg.getLogicDeviceUniqueId(), cfg.getAttrId()),
                    gran, mode, cfg);
        }
        if (buckets.isEmpty()) {
            return 0;
        }
        int written;
        if (gran == AsmStatGranularity.MINUTE) {
            written = statMapper.upsertMinute(buckets);
        } else if (gran == AsmStatGranularity.FIVE_MIN) {
            written = statMapper.upsertFiveMin(buckets);
        } else {
            written = statMapper.upsertHour(buckets);
        }
        return written;
    }

    /** minute←raw 分桶聚合：非空样本算术均值；valid=非空数、total=全部样本数；全空桶不产行。 */
    private List<AsmStatBucket> aggregateMinute(List<AsmRawRow> samples, AsmIntervalMode mode, AsmConfigStat cfg) {
        Map<Instant, List<AsmRawRow>> byBucket = new LinkedHashMap<>();
        for (AsmRawRow s : samples) {
            Instant label = AsmStatGridBucketing.truncateToGrid(s.getDataTime(), AsmStatGranularity.MINUTE, mode);
            byBucket.computeIfAbsent(label, k -> new ArrayList<>()).add(s);
        }
        List<AsmStatBucket> buckets = new ArrayList<>(byBucket.size());
        for (Map.Entry<Instant, List<AsmRawRow>> e : byBucket.entrySet()) {
            BigDecimal sum = BigDecimal.ZERO;
            long valid = 0;
            for (AsmRawRow s : e.getValue()) {
                if (s.getValueNum() != null) {
                    sum = sum.add(s.getValueNum());
                    valid++;
                }
            }
            if (valid == 0) {
                continue;  // 全空桶：无均值可写，不产行（avg-only 无 statuses 可表达）
            }
            buckets.add(AsmStatBucket.builder()
                    .dataTime(e.getKey())
                    .logicDeviceUniqueId(cfg.getLogicDeviceUniqueId())
                    .attrId(cfg.getAttrId())
                    .intervalMode(mode.code())
                    .avgValue(sum.divide(BigDecimal.valueOf(valid), 6, RoundingMode.HALF_EVEN).doubleValue())
                    .validCount(valid)
                    .totalCount((long) e.getValue().size())
                    .build());
        }
        return buckets;
    }

    /** 级联分桶聚合（5min/hour←minute）：按子桶 valid_count 加权均值；父计数=子计数求和；无有效子桶不产行。 */
    private List<AsmStatBucket> aggregateCascade(List<AsmStatBucket> children, AsmStatGranularity gran,
                                                 AsmIntervalMode mode, AsmConfigStat cfg) {
        Map<Instant, List<AsmStatBucket>> byBucket = new LinkedHashMap<>();
        for (AsmStatBucket c : children) {
            Instant label = AsmStatGridBucketing.truncateToGrid(c.getDataTime(), gran, mode);
            byBucket.computeIfAbsent(label, k -> new ArrayList<>()).add(c);
        }
        List<AsmStatBucket> buckets = new ArrayList<>(byBucket.size());
        for (Map.Entry<Instant, List<AsmStatBucket>> e : byBucket.entrySet()) {
            BigDecimal weightedSum = BigDecimal.ZERO;
            long validSum = 0;
            long totalSum = 0;
            for (AsmStatBucket c : e.getValue()) {
                totalSum += c.getTotalCount() == null ? 0 : c.getTotalCount();
                long v = c.getValidCount() == null ? 0 : c.getValidCount();
                if (v > 0 && c.getAvgValue() != null) {
                    weightedSum = weightedSum.add(BigDecimal.valueOf(c.getAvgValue()).multiply(BigDecimal.valueOf(v)));
                    validSum += v;
                }
            }
            if (validSum == 0) {
                continue;
            }
            buckets.add(AsmStatBucket.builder()
                    .dataTime(e.getKey())
                    .logicDeviceUniqueId(cfg.getLogicDeviceUniqueId())
                    .attrId(cfg.getAttrId())
                    .intervalMode(mode.code())
                    .avgValue(weightedSum.divide(BigDecimal.valueOf(validSum), 6, RoundingMode.HALF_EVEN).doubleValue())
                    .validCount(validSum)
                    .totalCount(totalSum)
                    .build());
        }
        return buckets;
    }

    /** 取窗口内 raw 样本（minute 源）：data_time + value_num 窄投影；WHERE 开闭随 mode。 */
    private List<AsmRawRow> fetchRawSamples(AsmIntervalMode mode, Instant ws, Instant we, String uid, String attrId) {
        String sql = "select src.data_time, src.value_num from asm_data_sample src"
                + " where " + whereExpression(mode)
                + " and src.logic_device_unique_id = ? and src.attr_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> AsmRawRow.of(
                        toInstant(rs.getObject("data_time")),
                        toBigDecimal(rs.getObject("value_num"))),
                toOdt(ws), toOdt(we), uid, attrId);
    }

    /** 取窗口内 minute 子桶（级联源）：恒带 interval_mode 自洽过滤。 */
    private List<AsmStatBucket> fetchChildBuckets(AsmStatGranularity gran, AsmIntervalMode mode,
                                                  Instant ws, Instant we, String uid, String attrId) {
        String sql = "select src.data_time, src.avg_value, src.valid_count, src.total_count from "
                + gran.sourceTable() + " src"
                + " where " + whereExpression(mode)
                + " and src.logic_device_unique_id = ? and src.attr_id = ?"
                + " and src.interval_mode = " + mode.code();
        return jdbcTemplate.query(sql, (rs, rowNum) -> AsmStatBucket.builder()
                        .dataTime(toInstant(rs.getObject("data_time")))
                        .avgValue(toNullableDouble(rs.getObject("avg_value")))
                        .validCount(rs.getLong("valid_count"))
                        .totalCount(rs.getLong("total_count"))
                        .build(),
                toOdt(ws), toOdt(we), uid, attrId);
    }

    /** 窗口 WHERE 开闭（与 AsmStatGridBucketing 桶归属镜像）：FRONT 左闭右开 / BACK 左开右闭。 */
    private static String whereExpression(AsmIntervalMode mode) {
        if (mode == AsmIntervalMode.BACK) {
            return "src.data_time > ? and src.data_time <= ?";
        }
        return "src.data_time >= ? and src.data_time < ?";
    }

    /** 写一行物化审计（SUCCESS/FAILED）；审计写本身失败 catch + error 不掩盖物化结果/异常。 */
    private void writeAudit(AsmStatGranularity gran, AsmIntervalMode mode, String trigger,
                            Instant ws, Instant we, String status, Integer bucketCount, Instant now, String error) {
        try {
            computeLogMapper.insert(AsmStatComputeLog.builder()
                    .granularity(gran.dbCode())
                    .triggerSource(trigger)
                    .windowStart(ws)
                    .windowEnd(we)
                    .intervalMode(mode.name())
                    .bucketCount(bucketCount)
                    .startedAt(now)
                    .endedAt(now)
                    .status(status)
                    .error(error)
                    .build());
        } catch (RuntimeException e) {
            log.error("[诊断调试] ASM 计算审计写入失败（不影响物化）: gran={} mode={} status={}: ",
                    new Object[]{gran.dbCode(), mode, status, e});
        }
    }

    // ===== 类型转换辅助（JdbcTemplate 绕过 MyBatis TypeHandler，自带等价转换；严格模式非预期类型抛）=====

    private static Instant toInstant(Object dbTime) {
        if (dbTime instanceof OffsetDateTime) {
            return ((OffsetDateTime) dbTime).toInstant();
        }
        if (dbTime instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) dbTime).toInstant();
        }
        if (dbTime instanceof Instant) {
            return (Instant) dbTime;
        }
        throw new IllegalStateException("[诊断调试] data_time 列非 OffsetDateTime/Timestamp/Instant：" + dbTime.getClass());
    }

    private static OffsetDateTime toOdt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        throw new IllegalStateException("[诊断调试] value_num 非 Number 类型：" + v.getClass());
    }

    private static Double toNullableDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        throw new IllegalStateException("[诊断调试] avg_value 非 Number 类型：" + v.getClass());
    }
}
