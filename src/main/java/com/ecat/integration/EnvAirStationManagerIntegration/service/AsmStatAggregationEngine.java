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
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatSeriesKind;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatSeriesKindClassifier;
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
 * ASM 三级聚合引擎（D3：ASM 内新写精简引擎，不依赖 ADM jar；series 按 {@link AsmStatSeriesKind}
 * 分流 AVG 数值 / ALARM 报警 / STATE 状态三套口径）。
 *
 * <p>应用场景：对 window 内的 raw 样本 / minute 子桶（fetch 时间范围，非桶边界）按
 * {@link AsmStatGranularity} 分桶产出桶行（数值 avg_value / 非数值 value_text + valid_count/total_count），
 * 经 {@link AsmStatMapper} ON CONFLICT 4 列 PK upsert（幂等，重算/回补安全）。聚合全在 Java，
 * DB 只 SELECT WHERE 取源行（零 SQL 聚合）。</p>
 *
 * <p><b>聚合语义（精简，无 O3-8h/PM 代表值/单位换算/有效性四态；kind 判定真相源在
 * {@link AsmStatSeriesKindClassifier} 白名单）</b>：
 * <ul>
 *   <li>AVG minute←raw：桶均值 = 窗口内非空 value_num 算术均值；valid_count = 非空样本数、
 *       total_count = 窗口内全部样本数；全空桶（valid=0）不写行。</li>
 *   <li>AVG 5min/hour←minute 级联：均值按子桶 <b>valid_count 加权</b>（sum(avg*valid)/sum(valid)，
 *       非 mean-of-means 简单平均——子桶样本数不等时 mean-of-means 有偏）；父 valid/total =
 *       子桶计数求和；无任何有效子桶的父桶不写行。</li>
 *   <li>ALARM minute←raw：窗口内任一非空文本样本='alarm' → 'alarm'；全 normal → 'normal'；
 *       无非空文本样本不写行（无数据 ≠ normal，历史查询可区分）。</li>
 *   <li>ALARM 5min←minute：桶标时刻点采样（对齐国标报表口径）；hour←minute：窗口内任一 minute
 *       行='alarm' → 'alarm'（全窗 OR 防漏报，用户修正不用 00 分点采样）。</li>
 *   <li>STATE minute←raw：距桶标时刻 |dataTime-桶标| 最小的样本值，等距取 dataTime 更新者；
 *       5min/hour←minute：桶标时刻点采样（hour 即整点 00 分行）；缺行不写父行。</li>
 *   <li>计数（非数值）：minute 行 valid=窗口内非空文本样本数 / total=窗口全部样本数；5min/hour
 *       点采样行<b>继承</b>所取 minute 行计数；hour ALARM OR 行=成员 minute 行计数<b>求和</b>。</li>
 *   <li>互斥不变式：非数值行 avgValue=null / 数值行 valueText=null，构建处保证（数值 avg 值槽与
 *       文本值槽不混写，与 raw 层 value_num/value_text 同构）。</li>
 * </ul></p>
 *
 * <p><b>门控</b>：只物化有 asm_config_stat 行且 enabled=true 的 series；granularity_mask 位未开的
 * 粒度跳过；materialization_mode（FRONT/BACK/BOTH）决定跑哪些区间模式——FRONT-only 不产 BACK 行。</p>
 *
 * <p><b>mode 依赖 WHERE + 桶标</b>（单一真相源 {@link AsmIntervalMode} / {@link AsmStatGridBucketing}）：
 * FRONT [S,E) 桶标=左沿、WHERE {@code >= ? and < ?}；BACK (L,R] 桶标=右沿、WHERE {@code > ? and <= ?}。
 * 子桶 fetch 恒带 {@code interval_mode = code} 过滤（级联 mode 自洽：FRONT 子桶→FRONT 父桶）；
 * fetch 上界钳制 gridFloor(now)——WHERE 开闭天然承载桶完整性（BACK 含等收边界桶 / FRONT 排等
 * 排进行中桶），未完桶不进聚合。入口左沿网格对齐 + 此处右沿 now 闭合对称构成
 * 「重算窗 = 完整网格桶集合且全部已闭合」。</p>
 *
 * <p><b>计算审计</b>：每 (粒度, mode) 物化写一行 asm_stat_compute_log（SUCCESS 记 bucketCount /
 * FAILED 记 error 后上抛）；单 series 失败隔离跳过（其余 series 继续），全 series 失败才整体 FAILED+上抛。
 * 时间源注入（{@code now} 参数）——调度/测试传同一 Instant 确定性落 started/ended，无隐式时钟；
 * 同一 now 也是 fetch 右沿闭合的钳制基准（上界 = min(we+pad, gridFloor(now))，进行中桶不进聚合）。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmStatAggregationEngine {

    /**
     * fetch 扩窗宽度：每端外扩 1 个该粒度桶（覆盖网格错位下首/末桶被窗口边界部分覆盖的情形）。
     * 左扩恒为纯保险；右扩受 now 闭合钳制（上界 = min(we+pad, gridFloor(now))）——历史重算窗
     * 保留右扩保险，会越过 now 的右扩被钳回最后一个完整桶边界，永不触及进行中的桶。
     * upsert 幂等覆写无害的前提是<b>完整证据重算</b>：入口网格对齐保证重算桶成员全集，
     * 右沿 now 闭合保证只算已闭合桶（幂等覆盖≠无害写入）。
     */
    private static final int FETCH_PAD_BUCKETS = 1;

    /** ALARM series 的报警态值串（raw 层 value_text 值域 normal/alarm；字面量判定，拼写由引擎单测锁死）。 */
    private static final String ALARM_VALUE = "alarm";

    /** ALARM series 的正常态值串。 */
    private static final String NORMAL_VALUE = "normal";

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
     * @param now          时间源（fetch 右沿闭合钳制基准 gridFloor(now) + 审计 started/ended 落此值；
     *                     调度器传 tick 时刻——进行中桶不进聚合，调用方注入保证确定性）
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
                bucketCount += materializeOneSeries(gran, mode, cfg, ws, we, now);
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

    /**
     * 单 series 一轮：fetch（左扩 1 桶 + 右沿钳制 gridFloor(now)）→ kind 分流聚合 → upsert。
     * 空产出（无源行/全空桶）返 0。fetch 侧右沿闭合后聚合输入恒为已闭合桶的成员全集——
     * 未完桶从取数起就不存在（不是算完再滤）。
     */
    private int materializeOneSeries(AsmStatGranularity gran, AsmIntervalMode mode,
                                     AsmConfigStat cfg, Instant ws, Instant we, Instant now) {
        Duration pad = gran.interval().multipliedBy(FETCH_PAD_BUCKETS);
        Instant fetchStart = ws.minus(pad);
        // 右沿闭合（fetch 侧）：上界 = min(we+pad, gridFloor(now))。BACK label L 完整 ⟺ L ≤ now ⟺
        // 成员 t ≤ gridFloor(now)（WHERE 含等恰使边界桶 label==gridFloor(now) 的成员全量入内）；
        // FRONT label S 完整 ⟺ S+interval ≤ now ⟺ 成员 t < gridFloor(now)（WHERE 排等天然排除
        // 进行中桶成员）——完整性判定由各 mode 的 WHERE 开闭承载，一个 frontier、零特判。
        // 取小使历史重算窗（we+pad 不越 now）保留右扩保险，只有会越过 now 的右扩被钳回。
        // 附带收益：库中遗留的未来时间戳子行（&gt; now，旧版本写入的脏行）同被前沿挡在 fetch 外，
        // 父桶聚合不被污染。
        Instant paddedEnd = we.plus(pad);
        Instant lastClosedEdge = AsmStatGridBucketing.truncateToGrid(now, gran, AsmIntervalMode.FRONT);
        Instant fetchEnd = paddedEnd.isBefore(lastClosedEdge) ? paddedEnd : lastClosedEdge;
        AsmStatSeriesKind kind = AsmStatSeriesKindClassifier.kindOf(cfg.getAttrId());
        List<AsmStatBucket> buckets;
        if (gran == AsmStatGranularity.MINUTE) {
            buckets = aggregateMinuteByKind(kind,
                    fetchRawSamples(mode, fetchStart, fetchEnd, cfg.getLogicDeviceUniqueId(), cfg.getAttrId()),
                    mode, cfg);
        } else {
            buckets = aggregateCascadeByKind(kind,
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

    /**
     * minute←raw 按 series 类别分流。ALARM/STATE 各自独立口径方法，不混入 AVG 主路径
     * （数值均值行为保持既有不变）；classifier 返 NONE = 数值 series——seed 准入已挡非统计对象
     * 进 asm_config_stat，引擎上下文内 NONE 只有数值一种形态，落 AVG 路径。
     */
    private List<AsmStatBucket> aggregateMinuteByKind(AsmStatSeriesKind kind, List<AsmRawRow> samples,
                                                      AsmIntervalMode mode, AsmConfigStat cfg) {
        if (kind == AsmStatSeriesKind.ALARM) {
            return aggregateAlarmMinute(samples, mode, cfg);
        }
        if (kind == AsmStatSeriesKind.STATE) {
            return aggregateStateMinute(samples, mode, cfg);
        }
        return aggregateMinute(samples, mode, cfg);
    }

    /**
     * 5min/hour←minute 按 series 类别分流。STATE 两粒度均为桶标点采样；ALARM 仅 5min 点采样
     * （对齐国标报表口径）、hour 为全窗 OR（防漏报，两粒度口径不同）；AVG 保持既有加权级联。
     */
    private List<AsmStatBucket> aggregateCascadeByKind(AsmStatSeriesKind kind, List<AsmStatBucket> children,
                                                       AsmStatGranularity gran, AsmIntervalMode mode, AsmConfigStat cfg) {
        if (kind == AsmStatSeriesKind.STATE) {
            return aggregatePointSampledMinute(children, gran, mode, cfg);
        }
        if (kind == AsmStatSeriesKind.ALARM) {
            return gran == AsmStatGranularity.HOUR
                    ? aggregateAlarmHourFromMinute(children, mode, cfg)
                    : aggregatePointSampledMinute(children, gran, mode, cfg);
        }
        return aggregateCascade(children, gran, mode, cfg);
    }

    /** minute←raw 分桶聚合（AVG 数值）：非空样本算术均值；valid=非空数、total=全部样本数；全空桶不产行。 */
    private List<AsmStatBucket> aggregateMinute(List<AsmRawRow> samples, AsmIntervalMode mode, AsmConfigStat cfg) {
        List<AsmStatBucket> buckets = new ArrayList<>();
        for (Map.Entry<Instant, List<AsmRawRow>> e : groupRawByMinuteBucket(samples, mode).entrySet()) {
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

    /**
     * ALARM minute←raw：小窗 OR 口径——窗口内任一非空文本样本 = 'alarm' 即 'alarm'（期间发生过
     * 报警即记录）；全 normal → 'normal'；无非空文本样本不产行（无数据 ≠ normal，历史查询可区分
     * 「正常」与「无数据」）。计数：valid=窗口内非空文本样本数、total=窗口全部样本数。
     */
    private List<AsmStatBucket> aggregateAlarmMinute(List<AsmRawRow> samples, AsmIntervalMode mode, AsmConfigStat cfg) {
        List<AsmStatBucket> buckets = new ArrayList<>();
        for (Map.Entry<Instant, List<AsmRawRow>> e : groupRawByMinuteBucket(samples, mode).entrySet()) {
            boolean alarm = false;
            long valid = 0;
            for (AsmRawRow s : e.getValue()) {
                if (hasText(s)) {
                    valid++;
                    alarm = alarm || ALARM_VALUE.equals(s.getValueText());
                }
            }
            if (valid == 0) {
                continue;
            }
            buckets.add(textBucket(e.getKey(), mode, cfg, alarm ? ALARM_VALUE : NORMAL_VALUE,
                    valid, e.getValue().size()));
        }
        return buckets;
    }

    /**
     * STATE minute←raw：点状态口径——取距桶标时刻 |dataTime-桶标| 最小的非空文本样本（FRONT 桶标
     * =左沿偏早样本、BACK=右沿偏晚样本，桶窗开闭与 AVG 同一 {@link AsmStatGridBucketing} 定义，
     * 仅聚合函数不同）；等距取 dataTime 更新者；无非空文本样本不产行。计数与 ALARM minute 同形。
     */
    private List<AsmStatBucket> aggregateStateMinute(List<AsmRawRow> samples, AsmIntervalMode mode, AsmConfigStat cfg) {
        List<AsmStatBucket> buckets = new ArrayList<>();
        for (Map.Entry<Instant, List<AsmRawRow>> e : groupRawByMinuteBucket(samples, mode).entrySet()) {
            Instant label = e.getKey();
            AsmRawRow nearest = null;
            long valid = 0;
            for (AsmRawRow s : e.getValue()) {
                if (!hasText(s)) {
                    continue;
                }
                valid++;
                if (nearest == null || nearerToLabel(s, nearest, label)) {
                    nearest = s;
                }
            }
            if (nearest == null) {
                continue;
            }
            buckets.add(textBucket(label, mode, cfg, nearest.getValueText(), valid, e.getValue().size()));
        }
        return buckets;
    }

    /**
     * 级联分桶聚合（AVG 数值，5min/hour←minute）：按子桶 valid_count 加权均值；父计数=子计数求和；
     * 无有效子桶不产行。
     */
    private List<AsmStatBucket> aggregateCascade(List<AsmStatBucket> children, AsmStatGranularity gran,
                                                 AsmIntervalMode mode, AsmConfigStat cfg) {
        List<AsmStatBucket> buckets = new ArrayList<>();
        for (Map.Entry<Instant, List<AsmStatBucket>> e : groupChildrenByBucket(children, gran, mode).entrySet()) {
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

    /**
     * 非数值级联点采样（5min ALARM/STATE、hour STATE）：取桶标时刻同 mode 的 minute 行——FRONT
     * 桶标=左沿（10:05 桶 → minute 10:05 行）、BACK 桶标=右沿（10:10 桶 → minute 10:10 行）、
     * hour 桶标即整点 00 分行；计数<b>继承</b>所取 minute 行（点采样不叠加）；缺行不写父行。
     */
    private List<AsmStatBucket> aggregatePointSampledMinute(List<AsmStatBucket> children, AsmStatGranularity gran,
                                                            AsmIntervalMode mode, AsmConfigStat cfg) {
        List<AsmStatBucket> buckets = new ArrayList<>();
        for (Map.Entry<Instant, List<AsmStatBucket>> e : groupChildrenByBucket(children, gran, mode).entrySet()) {
            Instant label = e.getKey();
            for (AsmStatBucket c : e.getValue()) {
                if (label.equals(c.getDataTime())) {
                    buckets.add(textBucket(label, mode, cfg, c.getValueText(),
                            c.getValidCount() == null ? 0L : c.getValidCount(),
                            c.getTotalCount() == null ? 0L : c.getTotalCount()));
                    break;  // minute 行 (data_time, uid, attr, mode) 是 PK，桶标时刻至多一行，命中即止
                }
            }
        }
        return buckets;
    }

    /**
     * hour ALARM←minute：全窗 OR 口径（用户修正，不用 00 分点采样防漏报）——窗口内同 mode 任一
     * minute 行 = 'alarm' 即 'alarm'；全 normal → 'normal'；无 minute 行不产行（分组只从实际子行
     * 建立，空组天然无行）。计数=成员 minute 行 valid/total 各自<b>求和</b>（报警覆盖时长的证据量）。
     */
    private List<AsmStatBucket> aggregateAlarmHourFromMinute(List<AsmStatBucket> children, AsmIntervalMode mode,
                                                             AsmConfigStat cfg) {
        List<AsmStatBucket> buckets = new ArrayList<>();
        for (Map.Entry<Instant, List<AsmStatBucket>> e : groupChildrenByBucket(children, AsmStatGranularity.HOUR, mode).entrySet()) {
            boolean alarm = false;
            long validSum = 0;
            long totalSum = 0;
            for (AsmStatBucket c : e.getValue()) {
                validSum += c.getValidCount() == null ? 0L : c.getValidCount();
                totalSum += c.getTotalCount() == null ? 0L : c.getTotalCount();
                alarm = alarm || ALARM_VALUE.equals(c.getValueText());
            }
            buckets.add(textBucket(e.getKey(), mode, cfg, alarm ? ALARM_VALUE : NORMAL_VALUE, validSum, totalSum));
        }
        return buckets;
    }

    /**
     * 非数值桶行唯一构建出口（ALARM/STATE 各策略共用）：valueText 落值、avgValue 恒不设
     * （builder 缺省 null）——互斥不变式集中在构建处一处保证、一处可检。
     */
    private static AsmStatBucket textBucket(Instant label, AsmIntervalMode mode, AsmConfigStat cfg,
                                            String valueText, long valid, long total) {
        return AsmStatBucket.builder()
                .dataTime(label)
                .logicDeviceUniqueId(cfg.getLogicDeviceUniqueId())
                .attrId(cfg.getAttrId())
                .intervalMode(mode.code())
                .valueText(valueText)
                .validCount(valid)
                .totalCount(total)
                .build();
    }

    /**
     * STATE 选样判据：候选距桶标更近者胜；等距取 dataTime 更新者。几何事实：分钟桶内样本恒在桶标
     * 同侧（FRONT 标=左沿 / BACK 标=右沿），|dataTime-桶标| 对不同 dataTime 严格单调——等距分支
     * 仅在<b>同一 dataTime 的重复 raw 行</b>（asm_data_sample 无主键约束，允许同刻多行）时到达，
     * 此时 isAfter 恒 false、保留首行；保留该分支是规则原文（等距取新）的逐字实现。
     */
    private static boolean nearerToLabel(AsmRawRow candidate, AsmRawRow incumbent, Instant label) {
        int cmp = Duration.between(label, candidate.getDataTime()).abs()
                .compareTo(Duration.between(label, incumbent.getDataTime()).abs());
        return cmp < 0 || (cmp == 0 && candidate.getDataTime().isAfter(incumbent.getDataTime()));
    }

    /** raw 样本是否带非空文本值（非数值 series 的有效样本判据；null/空串不计入 valid 与口径判定）。 */
    private static boolean hasText(AsmRawRow s) {
        return s.getValueText() != null && !s.getValueText().isEmpty();
    }

    /** raw 样本按 minute 桶标分组（minute 三口径共用；LinkedHashMap 保时间序，输出行序稳定可复现）。 */
    private static Map<Instant, List<AsmRawRow>> groupRawByMinuteBucket(List<AsmRawRow> samples, AsmIntervalMode mode) {
        Map<Instant, List<AsmRawRow>> byBucket = new LinkedHashMap<>();
        for (AsmRawRow s : samples) {
            Instant label = AsmStatGridBucketing.truncateToGrid(s.getDataTime(), AsmStatGranularity.MINUTE, mode);
            byBucket.computeIfAbsent(label, k -> new ArrayList<>()).add(s);
        }
        return byBucket;
    }

    /** minute 子桶按父粒度桶标分组（级联各口径共用；保序同上）。 */
    private static Map<Instant, List<AsmStatBucket>> groupChildrenByBucket(List<AsmStatBucket> children,
                                                                           AsmStatGranularity gran, AsmIntervalMode mode) {
        Map<Instant, List<AsmStatBucket>> byBucket = new LinkedHashMap<>();
        for (AsmStatBucket c : children) {
            Instant label = AsmStatGridBucketing.truncateToGrid(c.getDataTime(), gran, mode);
            byBucket.computeIfAbsent(label, k -> new ArrayList<>()).add(c);
        }
        return byBucket;
    }

    /** 取窗口内 raw 样本（minute 源）：data_time + value_num + value_text 窄投影（数值/非数值槽都带）；WHERE 开闭随 mode。 */
    private List<AsmRawRow> fetchRawSamples(AsmIntervalMode mode, Instant ws, Instant we, String uid, String attrId) {
        String sql = "select src.data_time, src.value_num, src.value_text from asm_data_sample src"
                + " where " + whereExpression(mode)
                + " and src.logic_device_unique_id = ? and src.attr_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> AsmRawRow.of(
                        toInstant(rs.getObject("data_time")),
                        toBigDecimal(rs.getObject("value_num")),
                        rs.getString("value_text")),
                toOdt(ws), toOdt(we), uid, attrId);
    }

    /** 取窗口内 minute 子桶（级联源）：恒带 interval_mode 自洽过滤；value_text 供非数值点采样/OR。 */
    private List<AsmStatBucket> fetchChildBuckets(AsmStatGranularity gran, AsmIntervalMode mode,
                                                  Instant ws, Instant we, String uid, String attrId) {
        String sql = "select src.data_time, src.avg_value, src.valid_count, src.total_count, src.value_text from "
                + gran.sourceTable() + " src"
                + " where " + whereExpression(mode)
                + " and src.logic_device_unique_id = ? and src.attr_id = ?"
                + " and src.interval_mode = " + mode.code();
        return jdbcTemplate.query(sql, (rs, rowNum) -> AsmStatBucket.builder()
                        .dataTime(toInstant(rs.getObject("data_time")))
                        .avgValue(toNullableDouble(rs.getObject("avg_value")))
                        .validCount(rs.getLong("valid_count"))
                        .totalCount(rs.getLong("total_count"))
                        .valueText(rs.getString("value_text"))
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
