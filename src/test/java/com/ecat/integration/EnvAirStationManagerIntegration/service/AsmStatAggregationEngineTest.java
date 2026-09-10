package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatComputeLog;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmStatComputeLogMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmGranularityMask;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ASM avg-only 聚合引擎单测——minute←raw 窗口均值 / 级联按 valid_count 加权（非 mean-of-means）/
 * mask 门控 / mode 门控（FRONT-only 不产 BACK 行）/ 幂等 upsert / compute_log 行 / enabled 过滤 /
 * 全 series 失败记 FAILED 审计并上抛。JdbcTemplate mock 取数（聚合全在 Java），无 DB 无 sleep。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmStatAggregationEngineTest {

    private static final String UID = "logicdevice_station.th";
    private static final String ATTR = "temperature";
    private static final Instant NOW = Instant.parse("2026-08-18T10:10:00Z");
    private static final Instant WS = Instant.parse("2026-08-18T10:08:00Z");
    private static final Instant WE = Instant.parse("2026-08-18T10:10:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private AsmConfigStatMapper configStatMapper;
    @Mock
    private AsmStatMapper statMapper;
    @Mock
    private AsmStatComputeLogMapper computeLogMapper;
    @Mock
    private AsmStatPartitionManager partitionManager;

    private AsmStatAggregationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AsmStatAggregationEngine(jdbcTemplate, configStatMapper, statMapper,
                computeLogMapper, partitionManager);
    }

    private static AsmConfigStat config(Integer mask, String mode, Boolean enabled) {
        return AsmConfigStat.builder()
                .logicDeviceUniqueId(UID).attrId(ATTR)
                .enabled(enabled).granularityMask(mask).materializationMode(mode).build();
    }

    private static AsmRawRow raw(String time, String value) {
        return AsmRawRow.of(Instant.parse(time), value == null ? null : new BigDecimal(value));
    }

    @Test
    @SuppressWarnings("unchecked")
    void minuteFromRaw_aggregatesWindowMeanAndCounts() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.ALL, "FRONT", true)));
        // 10:08 桶 3 样本（1 null 非有效）+ 10:09 桶 1 样本
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                raw("2026-08-18T10:08:10Z", "1.0"),
                raw("2026-08-18T10:08:20Z", "3.0"),
                raw("2026-08-18T10:08:30Z", null),
                raw("2026-08-18T10:09:10Z", "5.0")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(2);

        int total = engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);

        assertEquals(2, total);
        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        List<AsmStatBucket> buckets = cap.getValue();
        assertEquals(2, buckets.size());
        // 10:08 桶（BACK 右沿标 10:09? 不——minute granularity FRONT mode 先跑；本断言按 FRONT 标）
        AsmStatBucket b0 = buckets.get(0);
        assertEquals(Instant.parse("2026-08-18T10:08:00Z"), b0.getDataTime(), "FRONT 桶标=左沿");
        assertEquals(Integer.valueOf(AsmIntervalMode.FRONT.code()), b0.getIntervalMode());
        assertEquals(2.0, b0.getAvgValue(), 1e-9, "窗口均值 (1+3)/2");
        assertEquals(Long.valueOf(2), b0.getValidCount(), "null 样本不计有效");
        assertEquals(Long.valueOf(3), b0.getTotalCount());
        AsmStatBucket b1 = buckets.get(1);
        assertEquals(5.0, b1.getAvgValue(), 1e-9);
        assertEquals(Long.valueOf(1), b1.getValidCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cascade_weightedByValidCount_notMeanOfMeans() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.ALL, "FRONT", true)));
        // 两个 minute 子桶：avg=2 valid=1（权重 1）/ avg=4 valid=3（权重 3）→ 加权 (2*1+4*3)/4=3.5（mean-of-means=3 锁差异）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                AsmStatBucket.builder().dataTime(Instant.parse("2026-08-18T10:00:00Z"))
                        .avgValue(2.0).validCount(1L).totalCount(2L).build(),
                AsmStatBucket.builder().dataTime(Instant.parse("2026-08-18T10:02:00Z"))
                        .avgValue(4.0).validCount(3L).totalCount(3L).build()));
        when(statMapper.upsertFiveMin(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                Instant.parse("2026-08-18T10:00:00Z"), Instant.parse("2026-08-18T10:05:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertFiveMin(cap.capture());
        AsmStatBucket parent = cap.getValue().get(0);
        assertEquals(3.5, parent.getAvgValue(), 1e-9, "按 valid_count 加权 (2*1+4*3)/(1+3)");
        assertEquals(Long.valueOf(4), parent.getValidCount(), "父 valid=子 valid 求和");
        assertEquals(Long.valueOf(5), parent.getTotalCount(), "父 total=子 total 求和");
        assertEquals(Integer.valueOf(AsmIntervalMode.FRONT.code()), parent.getIntervalMode(), "级联 mode 自洽（FRONT 子桶→FRONT 父桶）");
    }

    @Test
    void maskGating_granularityBitOff_skipsSeries() {
        // mask=1（仅 minute）→ FIVE_MIN 物化跳过：不取数不 upsert 不写审计
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "BOTH", true)));

        int total = engine.materializeGranularity(AsmStatGranularity.FIVE_MIN, WS, WE, "SCHEDULE", NOW);

        assertEquals(0, total);
        verifyNoInteractions(statMapper, computeLogMapper);
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void modeGating_frontOnly_neverProducesBackRows() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "FRONT", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Collections.singletonList(
                raw("2026-08-18T10:08:10Z", "1.0")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        for (AsmStatBucket b : cap.getValue()) {
            assertEquals(Integer.valueOf(AsmIntervalMode.FRONT.code()), b.getIntervalMode(),
                    "FRONT-only 配置不得产 BACK(code=2) 行");
        }
        // 单 mode 只写一行审计（不写 BACK 审计）
        verify(computeLogMapper).insert(any(AsmStatComputeLog.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void idempotentRematerialize_sameWindowUpsertsSameBuckets() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "FRONT", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Collections.singletonList(
                raw("2026-08-18T10:08:10Z", "1.0")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);
        engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper, org.mockito.Mockito.times(2)).upsertMinute(cap.capture());
        List<AsmStatBucket> first = cap.getAllValues().get(0);
        List<AsmStatBucket> second = cap.getAllValues().get(1);
        assertEquals(first, second, "同窗口重物化 upsert 入参一致（ON CONFLICT 幂等）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void computeLog_successRowWrittenWithTriggerAndBucketCount() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.HOUR, "BACK", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Collections.singletonList(
                AsmStatBucket.builder().dataTime(Instant.parse("2026-08-18T09:59:00Z"))
                        .avgValue(7.0).validCount(2L).totalCount(2L).build()));
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.HOUR, WS, WE, "SCHEDULE", NOW);

        ArgumentCaptor<AsmStatComputeLog> cap = ArgumentCaptor.forClass(AsmStatComputeLog.class);
        verify(computeLogMapper).insert(cap.capture());
        AsmStatComputeLog row = cap.getValue();
        assertEquals("hour", row.getGranularity(), "dbCode 落库");
        assertEquals("SCHEDULE", row.getTriggerSource());
        assertEquals("BACK", row.getIntervalMode());
        assertEquals(Integer.valueOf(1), row.getBucketCount());
        assertEquals("SUCCESS", row.getStatus());
        // 审计窗=engine 入口网格对齐后的实际取数窗（WS=10:08 按 hour floor→10:00；对齐后窗才是真实计算窗）
        assertEquals(Instant.parse("2026-08-18T10:00:00Z"), row.getWindowStart());
        assertEquals(Instant.parse("2026-08-18T10:00:00Z"), row.getWindowEnd());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothModes_produceFrontAndBackBuckets() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "BOTH", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Collections.singletonList(
                raw("2026-08-18T10:08:10Z", "1.0")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);

        // BOTH 双趟：FRONT 与 BACK 各一趟，各产一行（桶标不同/同值），审计各一行
        verify(statMapper, org.mockito.Mockito.times(2)).upsertMinute(any(List.class));
        verify(computeLogMapper, org.mockito.Mockito.times(2)).insert(any(AsmStatComputeLog.class));
    }

    @Test
    void disabledSeries_skipped() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.ALL, "BOTH", false)));

        int total = engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);

        assertEquals(0, total);
        verifyNoInteractions(statMapper, computeLogMapper);
    }

    @Test
    void allSeriesFail_writesFailedAuditAndRethrows() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "FRONT", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW));

        ArgumentCaptor<AsmStatComputeLog> cap = ArgumentCaptor.forClass(AsmStatComputeLog.class);
        verify(computeLogMapper).insert(cap.capture());
        assertEquals("FAILED", cap.getValue().getStatus());
        assertEquals("db down", cap.getValue().getError());
    }

    /**
     * bug-record-20260818-225500 regression ①：调度 tick 相位（边界+delay 秒）传入 engine 的
     * ws/we 未对齐网格时，engine 入口须先 floor 对齐——fetch 收到的窗口参数必须是网格对齐形态，
     * 否则 pad 锚在相位 ws 上会把边界桶最早成员行截在 fetch 窗外。
     */
    @Test
    @SuppressWarnings("unchecked")
    void phasedWindow_engineAlignsFetchWindowToGrid() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "BACK", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // tick 相位窗：10:08:10.695 - 10:10:10.695（非网格对齐）
        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-08-18T10:08:10.695Z"), Instant.parse("2026-08-18T10:10:10.695Z"), "SCHEDULE", NOW);

        org.mockito.ArgumentCaptor<Object> args = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                args.capture(), args.capture(), args.capture(), args.capture());
        List<Object> captured = args.getAllValues();
        java.time.OffsetDateTime fetchStart = (java.time.OffsetDateTime) captured.get(0);
        java.time.OffsetDateTime fetchEnd = (java.time.OffsetDateTime) captured.get(1);
        // ws floor 到网格 10:08:00 再左扩 1 桶 pad = 10:07:00；we floor 10:10:00 + 1 桶 = 10:11:00
        assertEquals(Instant.parse("2026-08-18T10:07:00Z"), fetchStart.toInstant(),
                "fetchStart 须网格对齐（floor(ws)-pad），不得锚在相位 ws 上");
        assertEquals(Instant.parse("2026-08-18T10:11:00Z"), fetchEnd.toInstant(),
                "fetchEnd 须网格对齐（floor(we)+pad）");
    }

    /**
     * bug-record-20260818-225500 regression ②：边界桶成员完备——14:40:04 起的 11 行 raw
     * （5×26 + 6×23，BACK 桶 14:41=(14:40,14:41]）经相邻 3 个相位 tick 重算同桶，
     * 同桶 valid_count 不得回缩、终值恒为成员全集（avg=24.363636）。原缺陷：fetch 窗截掉
     * 14:40:04/09 两行 → 覆写回缩 9 行 avg=24.0。
     */
    @Test
    @SuppressWarnings("unchecked")
    void phasedTickRetrial_boundaryBucketNeverShrinks() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "BACK", true)));
        // 模拟 DB WHERE（BACK 语义 > start and <= end）：按引擎传入的 fetch 窗过滤全集
        List<AsmRawRow> all = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(raw(String.format("2026-08-18T14:40:%02d.500Z", 4 + i), "26"));
        }
        for (int i = 0; i < 6; i++) {
            all.add(raw(String.format("2026-08-18T14:40:%02d.500Z", 30 + i), "23"));
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.time.OffsetDateTime start = inv.getArgument(2);
                    java.time.OffsetDateTime end = inv.getArgument(3);
                    java.util.List<AsmRawRow> hit = new java.util.ArrayList<>();
                    for (AsmRawRow r : all) {
                        if (r.getDataTime().isAfter(start.toInstant()) && !r.getDataTime().isAfter(end.toInstant())) {
                            hit.add(r);
                        }
                    }
                    return hit;
                });
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        // 相邻 3 个相位 tick（bug 现场：tick 时刻 = 边界 + delay 秒相位）
        Instant[] tickEnds = {
                Instant.parse("2026-08-18T14:42:10.695Z"),
                Instant.parse("2026-08-18T14:43:10.695Z"),
                Instant.parse("2026-08-18T14:44:10.695Z")};
        for (Instant te : tickEnds) {
            engine.materializeGranularity(AsmStatGranularity.MINUTE, te.minusSeconds(120), te, "SCHEDULE", NOW);
        }

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        // 第 3 个 tick（ws=14:42:10）对齐后 fetchStart=14:41:00，桶 14:41 已滑出窗 → 不重算不覆写（正确）；
        // 前 2 个 tick 窗口覆盖桶 14:41，每次重算必须带全 11 个成员（修复前第 2 趟覆写回缩 9 行）。
        verify(statMapper, org.mockito.Mockito.times(2)).upsertMinute(cap.capture());
        for (List<AsmStatBucket> pass : cap.getAllValues()) {
            AsmStatBucket b41 = pass.stream()
                    .filter(b -> Instant.parse("2026-08-18T14:41:00Z").equals(b.getDataTime()))
                    .findFirst().orElseThrow(() -> new AssertionError("BACK 桶 14:41 缺失: " + pass));
            assertEquals(Long.valueOf(11), b41.getValidCount(), "边界桶成员不得被相位 fetch 窗截掉");
            assertEquals((5 * 26 + 6 * 23) / 11.0, b41.getAvgValue(), 1e-6, "终值=成员全集均值 24.363636");
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<AsmStatBucket>> bucketCaptor() {
        return ArgumentCaptor.forClass((Class<List<AsmStatBucket>>) (Class<?>) List.class);
    }
}
