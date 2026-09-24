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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /** 多 series 用例的按 attrId 配置构造（per-series 隔离/分流断言需要可区分的 series）。 */
    private static AsmConfigStat config(String attrId, Integer mask, String mode, Boolean enabled) {
        return AsmConfigStat.builder()
                .logicDeviceUniqueId(UID).attrId(attrId)
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

    /**
     * fetch WHERE 开闭按 mode 自洽（与 AsmStatGridBucketing 桶归属镜像）：FRONT 前闭右开
     * {@code >= ? and < ?}、BACK 左开右闭 {@code > ? and <= ?}——取数边界与分桶归属两处
     * 对同一时刻判定恒等（不重不漏）依赖此形态，锁死防漂移。
     */
    @Test
    @SuppressWarnings("unchecked")
    void whereClause_openClosedPerMode() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.MINUTE, "BOTH", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2))
                .query(sqlCap.capture(), any(RowMapper.class), any(), any(), any(), any());
        String front = sqlCap.getAllValues().stream().filter(s -> s.contains("src.data_time >= ?")).findFirst()
                .orElseThrow(() -> new AssertionError("缺 FRONT 前闭形态 SQL: " + sqlCap.getAllValues()));
        String back = sqlCap.getAllValues().stream().filter(s -> s.contains("src.data_time > ?")).findFirst()
                .orElseThrow(() -> new AssertionError("缺 BACK 后闭形态 SQL: " + sqlCap.getAllValues()));
        assertTrue(front.contains("src.data_time < ?"), "FRONT 右开：< ?");
        assertFalse(front.contains("src.data_time <= ?"), "FRONT 不得右闭");
        assertTrue(back.contains("src.data_time <= ?"), "BACK 右闭：<= ?");
        assertFalse(back.contains("src.data_time >= ?"), "BACK 不得左闭");
    }

    /** 级联子桶 fetch 的 interval_mode 自洽过滤：FRONT 物化只取 FRONT(=1) minute 子桶、BACK 只取 BACK(=2)，且开闭形态同 mode。 */
    @Test
    @SuppressWarnings("unchecked")
    void whereClause_cascadeChildFetch_intervalModeSelfConsistent() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(config(AsmGranularityMask.ALL, "BOTH", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                Instant.parse("2026-08-18T10:00:00Z"), Instant.parse("2026-08-18T10:15:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2))
                .query(sqlCap.capture(), any(RowMapper.class), any(), any(), any(), any());
        String front = sqlCap.getAllValues().stream().filter(s -> s.contains("src.interval_mode = 1")).findFirst()
                .orElseThrow(() -> new AssertionError("缺 FRONT(=1) 子桶过滤 SQL: " + sqlCap.getAllValues()));
        String back = sqlCap.getAllValues().stream().filter(s -> s.contains("src.interval_mode = 2")).findFirst()
                .orElseThrow(() -> new AssertionError("缺 BACK(=2) 子桶过滤 SQL: " + sqlCap.getAllValues()));
        assertTrue(front.contains("from asm_stat_minute src"), "级联源=minute 表");
        assertTrue(back.contains("from asm_stat_minute src"), "级联源=minute 表");
        assertTrue(front.contains("src.data_time >= ? and src.data_time < ?"), "FRONT 子桶取数前闭右开");
        assertTrue(back.contains("src.data_time > ? and src.data_time <= ?"), "BACK 子桶取数左开右闭");
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

    /** per-series 隔离（全灭路径的另一半）：两 series 其一 fetch 抛 RuntimeException——另一 series 照常物化 upsert、compute_log 记 SUCCESS（bucketCount 只计成功者）、整体不上抛。 */
    @Test
    @SuppressWarnings("unchecked")
    void oneSeriesFails_othersStillMaterialize_successAudit() {
        String attrOk = "temperature";
        String attrBad = "humidity";
        when(configStatMapper.selectAll()).thenReturn(Arrays.asList(
                config(attrOk, AsmGranularityMask.MINUTE, "FRONT", true),
                config(attrBad, AsmGranularityMask.MINUTE, "FRONT", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (attrBad.equals(inv.getArgument(5))) {
                        throw new IllegalStateException("series fetch boom");
                    }
                    return Collections.singletonList(raw("2026-08-18T10:08:10Z", "4.0"));
                });
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        int total = engine.materializeGranularity(AsmStatGranularity.MINUTE, WS, WE, "SCHEDULE", NOW);

        assertEquals(1, total, "返回桶数只计成功 series");
        verify(jdbcTemplate, times(2))
                .query(anyString(), any(RowMapper.class), any(), any(), any(), any());
        ArgumentCaptor<List<AsmStatBucket>> bucketCap = bucketCaptor();
        verify(statMapper).upsertMinute(bucketCap.capture());
        assertEquals(1, bucketCap.getValue().size());
        assertEquals(attrOk, bucketCap.getValue().get(0).getAttrId(), "失败 series 不产 upsert 行");
        ArgumentCaptor<AsmStatComputeLog> logCap = ArgumentCaptor.forClass(AsmStatComputeLog.class);
        verify(computeLogMapper).insert(logCap.capture());
        assertEquals("SUCCESS", logCap.getValue().getStatus(), "部分失败仍 SUCCESS（全灭才 FAILED+上抛）");
        assertEquals(Integer.valueOf(1), logCap.getValue().getBucketCount(), "bucketCount 只计成功 series");
        assertNull(logCap.getValue().getError());
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

        // tick 相位窗：10:08:10.695 - 10:10:10.695（非网格对齐）；now=10:12（≥ we+pad=10:11，历史窗
        // 形态）——右扩不被 now 钳制，本测保持纯左沿对齐聚焦（右沿钳制由 fetchClamp 三测独立锁）
        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-08-18T10:08:10.695Z"), Instant.parse("2026-08-18T10:10:10.695Z"), "SCHEDULE",
                Instant.parse("2026-08-18T10:12:00Z"));

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
            // now=te（生产接线 now=tick=窗端）：数据时间线在 14:4x，now 须落在桶闭合之后，
            // 否则重算桶全部按未完滤除、无 upsert 可断言（本回归锁左沿截断，与右沿正交）
            engine.materializeGranularity(AsmStatGranularity.MINUTE, te.minusSeconds(120), te, "SCHEDULE", te);
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

    // ===================== 右沿闭合（未完桶不进聚合、不写库） =====================

    /** 数值 minute 子桶（级联右沿用例）：整分标注 + avg/valid/total（各计 1，父计数=子行数直读）。 */
    private static AsmStatBucket numChild(Instant dataTime, double avg) {
        return AsmStatBucket.builder().dataTime(dataTime)
                .avgValue(avg).validCount(1L).totalCount(1L).build();
    }

    /** 模拟 DB BACK 语义 WHERE（> start and <= end）：按引擎传入的 fetch 窗过滤子桶全集。 */
    private static List<AsmStatBucket> hitBack(List<AsmStatBucket> all, OffsetDateTime start, OffsetDateTime end) {
        List<AsmStatBucket> hit = new ArrayList<>();
        for (AsmStatBucket c : all) {
            if (c.getDataTime().isAfter(start.toInstant()) && !c.getDataTime().isAfter(end.toInstant())) {
                hit.add(c);
            }
        }
        return hit;
    }

    /** 模拟 DB FRONT 语义 WHERE（>= start and < end）：与 hitBack 镜像，开闭差异即被测对象。 */
    private static List<AsmStatBucket> hitFront(List<AsmStatBucket> all, OffsetDateTime start, OffsetDateTime end) {
        List<AsmStatBucket> hit = new ArrayList<>();
        for (AsmStatBucket c : all) {
            if (!c.getDataTime().isBefore(start.toInstant()) && c.getDataTime().isBefore(end.toInstant())) {
                hit.add(c);
            }
        }
        return hit;
    }

    /**
     * 右沿闭合回归 ①（BACK 小时）：整点后 :03 的 hour 物化 fetch 右扩 1 桶会取到进行中小时
     * (15:00,16:00] 的部分分钟子桶（15:01..15:03）——未完父桶（BACK label=16:00，未来时间戳）
     * 不得落库；已闭合小时 (14:00,15:00]（桶尾=label=15:00 ≤ now）正常写。修前进行中小时带
     * 3/60 部分子桶落库成未来时间戳行，SDK/报表直读出口读到部分窗口均值。
     */
    @Test
    @SuppressWarnings("unchecked")
    void hourUnfinished_backMode_futureLabelNotWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.HOUR, "BACK", true)));
        // 已闭合小时 (14:00,15:00]：46 个分钟子桶（BACK 整分标注 14:01..14:46）；进行中小时：3 个（15:01..15:03）
        List<AsmStatBucket> children = new ArrayList<>();
        for (int m = 1; m <= 46; m++) {
            children.add(numChild(Instant.parse("2026-09-18T14:00:00Z").plusSeconds(m * 60L), 10.0));
        }
        for (int m = 1; m <= 3; m++) {
            children.add(numChild(Instant.parse("2026-09-18T15:00:00Z").plusSeconds(m * 60L), 10.0));
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> hitBack(children, inv.getArgument(2), inv.getArgument(3)));
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        Instant tick = Instant.parse("2026-09-18T15:03:10Z");
        engine.materializeGranularity(AsmStatGranularity.HOUR,
                tick.minusSeconds(2 * 3600), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertHour(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(1, rows.size(), "只写已闭合小时 label 15:00；进行中小时 BACK label 16:00（未来时间戳）不得落库: " + rows);
        AsmStatBucket closed = rows.get(0);
        assertAll("已闭合小时 (14:00,15:00] 完整写",
                () -> assertEquals(Instant.parse("2026-09-18T15:00:00Z"), closed.getDataTime(), "BACK label=右沿"),
                () -> assertEquals(10.0, closed.getAvgValue(), 1e-9),
                () -> assertEquals(Long.valueOf(46), closed.getValidCount(), "46 有效子桶全员"),
                () -> assertEquals(Long.valueOf(46), closed.getTotalCount()));
    }

    /**
     * 右沿闭合回归 ②（FRONT 小时）：[S,E) 语义下进行中小时 [15:00,16:00) 的 label=15:00
     * （当前小时起点，桶尾=16:00 > now）不得写成部分桶；已闭合小时 [14:00,15:00)（桶尾
     * 15:00 ≤ now）正常写。与回归 ① 共同锁两 mode 的桶尾判定（FRONT 桶尾=label+interval）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void hourUnfinished_frontMode_currentLabelNotWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.HOUR, "FRONT", true)));
        // 已闭合小时 [14:00,15:00)：60 个分钟子桶（FRONT 标注 14:00..14:59）；进行中小时：4 个（15:00..15:03）
        List<AsmStatBucket> children = new ArrayList<>();
        for (int m = 0; m < 60; m++) {
            children.add(numChild(Instant.parse("2026-09-18T14:00:00Z").plusSeconds(m * 60L), 10.0));
        }
        for (int m = 0; m <= 3; m++) {
            children.add(numChild(Instant.parse("2026-09-18T15:00:00Z").plusSeconds(m * 60L), 10.0));
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    OffsetDateTime start = inv.getArgument(2);
                    OffsetDateTime end = inv.getArgument(3);
                    List<AsmStatBucket> hit = new ArrayList<>();
                    for (AsmStatBucket c : children) {
                        if (!c.getDataTime().isBefore(start.toInstant()) && c.getDataTime().isBefore(end.toInstant())) {
                            hit.add(c);
                        }
                    }
                    return hit;
                });
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        Instant tick = Instant.parse("2026-09-18T15:03:10Z");
        engine.materializeGranularity(AsmStatGranularity.HOUR,
                tick.minusSeconds(2 * 3600), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertHour(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(1, rows.size(), "只写已闭合小时 label 14:00；进行中 FRONT 小时 label 15:00（桶尾 16:00 > now）不得落库: " + rows);
        AsmStatBucket closed = rows.get(0);
        assertAll("已闭合 FRONT 小时 [14:00,15:00) 完整写",
                () -> assertEquals(Instant.parse("2026-09-18T14:00:00Z"), closed.getDataTime(), "FRONT label=左沿"),
                () -> assertEquals(Long.valueOf(60), closed.getValidCount(), "60 有效子桶全员"),
                () -> assertEquals(Long.valueOf(60), closed.getTotalCount()));
    }

    /**
     * 右沿闭合回归 ③（BACK 分钟）：tick 14:41:10 的 minute 物化 fetch 右扩触及进行中分钟
     * (14:41,14:42]——BACK label=14:42（未来时间戳）不得写；已闭合分钟 (14:40,14:41]
     * （label 14:41 ≤ now）以成员全集写入。minute 层是右沿缺陷的最直接形态（未来时间戳行）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void minuteUnfinished_backMode_nextMinuteLabelNotWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.MINUTE, "BACK", true)));
        // 已闭合分钟 (14:40,14:41]：14:40:05/14:40:35 两样本；进行中分钟 (14:41,14:42]：14:41:05 单样本
        List<AsmRawRow> all = Arrays.asList(
                raw("2026-09-18T14:40:05Z", "26"),
                raw("2026-09-18T14:40:35Z", "23"),
                raw("2026-09-18T14:41:05Z", "20"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    OffsetDateTime start = inv.getArgument(2);
                    OffsetDateTime end = inv.getArgument(3);
                    List<AsmRawRow> hit = new ArrayList<>();
                    for (AsmRawRow r : all) {
                        if (r.getDataTime().isAfter(start.toInstant()) && !r.getDataTime().isAfter(end.toInstant())) {
                            hit.add(r);
                        }
                    }
                    return hit;
                });
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        Instant tick = Instant.parse("2026-09-18T14:41:10Z");
        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                tick.minusSeconds(120), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(1, rows.size(), "只写已闭合分钟 label 14:41；进行中分钟 BACK label 14:42（未来时间戳）不得落库: " + rows);
        AsmStatBucket closed = rows.get(0);
        assertAll("已闭合分钟 (14:40,14:41] 以成员全集写",
                () -> assertEquals(Instant.parse("2026-09-18T14:41:00Z"), closed.getDataTime(), "BACK label=右沿"),
                () -> assertEquals(24.5, closed.getAvgValue(), 1e-9, "(26+23)/2 全集均值"),
                () -> assertEquals(Long.valueOf(2), closed.getValidCount()),
                () -> assertEquals(Long.valueOf(2), closed.getTotalCount()));
    }

    /**
     * 右沿闭合回归 ④（恰边界保留侧——回归 ① 的镜像）：闭合判定是「桶尾 &gt; now 才丢」，
     * 桶尾（BACK label）恰等于 now 时边界含等、桶保留。now=15:00:00 整点重算：小时
     * (14:00,15:00] 的 60 个分钟子桶全员就绪、桶在该瞬时恰好闭合 → 必须写入。若误收严为
     * 桶尾 &lt; now，整点重算会丢掉刚闭合的小时（下一 tick 才补回）——与「未完桶不写」
     * 互为镜像，共同锁死边界语义。
     */
    @Test
    @SuppressWarnings("unchecked")
    void bucketEndExactlyNow_backBucketKept() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.HOUR, "BACK", true)));
        // 闭合小时 (14:00,15:00] 的 60 个分钟子桶（BACK 整分标注 14:01..15:00）
        List<AsmStatBucket> children = new ArrayList<>();
        for (int m = 59; m >= 0; m--) {
            children.add(numChild(Instant.parse("2026-09-18T15:00:00Z").minusSeconds(m * 60L), 10.0));
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> hitBack(children, inv.getArgument(2), inv.getArgument(3)));
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        Instant tick = Instant.parse("2026-09-18T15:00:00Z");
        engine.materializeGranularity(AsmStatGranularity.HOUR,
                tick.minusSeconds(2 * 3600), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertHour(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(1, rows.size(), "桶尾==now 的恰闭合小时桶须写入: " + rows);
        AsmStatBucket closed = rows.get(0);
        assertAll("恰边界（label==now）保留并完整写",
                () -> assertEquals(Instant.parse("2026-09-18T15:00:00Z"), closed.getDataTime(), "BACK label=右沿 15:00（== now）"),
                () -> assertEquals(Long.valueOf(60), closed.getValidCount(), "60 有效分钟子桶全员"),
                () -> assertEquals(Long.valueOf(60), closed.getTotalCount()));
    }

    /**
     * 右沿闭合参数层（minute raw 路径）：fetch 上界必须钳制到 gridFloor(now)——不是 we+pad。
     * 产出层回归（①②③）只能证明「没写入」；参数层证明「没计算」——fetch 越过 now 取回
     * 进行中桶成员、聚合后再丢弃是修前的半成品形态（算完再滤），本测锁死其不可复归。
     */
    @Test
    @SuppressWarnings("unchecked")
    void minuteFetchClamp_rawPath_endBoundToGridFloorOfNow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.MINUTE, "BACK", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // tick=14:41:10：窗对齐 we=14:41、we+pad=14:42；gridFloorMinute(now)=14:41 → fetchEnd 必须=14:41
        Instant tick = Instant.parse("2026-09-18T14:41:10Z");
        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                tick.minusSeconds(120), tick, "SCHEDULE", tick);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                args.capture(), args.capture(), args.capture(), args.capture());
        assertAll("minute raw fetch 右沿钳制（左沿不动作）",
                () -> assertEquals(Instant.parse("2026-09-18T14:38:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(0)).toInstant(),
                        "fetchStart 保持 floor(ws)-pad（左扩是纯保险，不受 now 钳制）"),
                () -> assertEquals(Instant.parse("2026-09-18T14:41:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(1)).toInstant(),
                        "fetchEnd 须钳制 gridFloor(now)=14:41（非 we+pad=14:42）——进行中分钟成员不进聚合"));
    }

    /**
     * 右沿闭合参数层（5min 级联路径）：钳制对 fetchChildBuckets 同样生效——级联取 minute
     * 子桶的上界也必须 = gridFloor(now)，不得越过 now 取进行中父桶的已就绪子桶。
     */
    @Test
    @SuppressWarnings("unchecked")
    void fiveMinFetchClamp_cascadePath_endBoundToGridFloorOfNow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.FIVE_MIN, "FRONT", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // tick=14:37:30：窗对齐 ws=14:20/we=14:35、we+pad=14:40；gridFloor5(now)=14:35 → fetchEnd 必须=14:35
        Instant tick = Instant.parse("2026-09-18T14:37:30Z");
        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                tick.minusSeconds(15 * 60), tick, "SCHEDULE", tick);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                args.capture(), args.capture(), args.capture(), args.capture());
        assertAll("5min 级联 fetch 右沿钳制",
                () -> assertEquals(Instant.parse("2026-09-18T14:15:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(0)).toInstant(),
                        "fetchStart 保持 floor(ws)-pad"),
                () -> assertEquals(Instant.parse("2026-09-18T14:35:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(1)).toInstant(),
                        "fetchEnd 须钳制 gridFloor(now)=14:35（非 we+pad=14:40）——进行中 5min 桶子桶不进聚合"));
    }

    /**
     * 右沿闭合参数层（hour 级联路径）：hour 取 minute 子桶的上界钳制 = gridFloorHour(now)。
     * 三粒度（minute raw / 5min、hour 级联）两路 fetch 全覆盖，钳制是粒度无关的结构不变量。
     */
    @Test
    @SuppressWarnings("unchecked")
    void hourFetchClamp_cascadePath_endBoundToGridFloorOfNow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.HOUR, "BACK", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // tick=15:03:10：窗对齐 ws=13:00/we=15:00、we+pad=16:00；gridFloorHour(now)=15:00 → fetchEnd 必须=15:00
        Instant tick = Instant.parse("2026-09-18T15:03:10Z");
        engine.materializeGranularity(AsmStatGranularity.HOUR,
                tick.minusSeconds(2 * 3600), tick, "SCHEDULE", tick);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                args.capture(), args.capture(), args.capture(), args.capture());
        assertAll("hour 级联 fetch 右沿钳制",
                () -> assertEquals(Instant.parse("2026-09-18T12:00:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(0)).toInstant(),
                        "fetchStart 保持 floor(ws)-pad"),
                () -> assertEquals(Instant.parse("2026-09-18T15:00:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(1)).toInstant(),
                        "fetchEnd 须钳制 gridFloor(now)=15:00（非 we+pad=16:00）——进行中小时子桶不进聚合"));
    }

    /**
     * 右沿闭合回归 ⑤（BACK 5min 级联，产出层）：tick 14:36:30 不得计算进行中桶
     * (14:35,14:40]（BACK label=14:40，桶尾 &gt; now）——已就绪的 14:36/14:37 分钟子桶不进
     * 聚合；已闭合桶 14:30/14:35 以成员全集（5/5）写入。补齐三粒度产出层全覆盖
     * （minute 回归 ③ / hour 回归 ① / 5min 本测）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void fiveMinUnfinished_backMode_nextLabelNotWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.FIVE_MIN, "BACK", true)));
        // 分钟子桶全集（BACK 整分标注 14:26..14:37）：14:26..14:35 属已闭合 5min 桶；14:36/14:37 属进行中桶 14:40
        List<AsmStatBucket> children = new ArrayList<>();
        for (int m = 26; m <= 37; m++) {
            children.add(numChild(Instant.parse("2026-09-18T14:00:00Z").plusSeconds(m * 60L), 10.0));
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> hitBack(children, inv.getArgument(2), inv.getArgument(3)));
        when(statMapper.upsertFiveMin(any(List.class))).thenReturn(2);

        Instant tick = Instant.parse("2026-09-18T14:36:30Z");
        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                tick.minusSeconds(15 * 60), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertFiveMin(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(2, rows.size(), "只写已闭合 5min 桶 14:30/14:35；进行中桶 BACK label 14:40 不得落库: " + rows);
        assertAll("已闭合 5min 桶成员全集写",
                () -> assertEquals(Instant.parse("2026-09-18T14:30:00Z"), rows.get(0).getDataTime(),
                        "BACK label=右沿 14:30"),
                () -> assertEquals(Long.valueOf(5), rows.get(0).getValidCount(), "14:30 桶 5/5 子桶"),
                () -> assertEquals(Long.valueOf(5), rows.get(0).getTotalCount()),
                () -> assertEquals(Instant.parse("2026-09-18T14:35:00Z"), rows.get(1).getDataTime(),
                        "BACK label=右沿 14:35"),
                () -> assertEquals(Long.valueOf(5), rows.get(1).getValidCount(), "14:35 桶 5/5 子桶"),
                () -> assertEquals(Long.valueOf(5), rows.get(1).getTotalCount()));
    }

    /**
     * 右沿闭合回归 ⑥（FRONT 分钟）：[S,E) 语义下进行中分钟 [14:41,14:42) 的 label=14:41
     * （当前分钟起点）不得写；已闭合分钟 [14:40,14:41)（label 14:40，桶尾 14:41 ≤ now）
     * 以成员全集写入。样本特意放在两处整分边界上：14:40:00 恰=已闭合桶左沿（`&gt;=` 含等
     * 必须收入）、14:41:00 恰=fetchEnd（`&lt;` 排等必须挡在聚合外）——FRONT 开闭两侧的
     * 边界样本行为在同一用例内对锁。补齐 minute 粒度 FRONT 侧（生产 174 series 全 BOTH，
     * FRONT 路径现网全量跑）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void minuteUnfinished_frontMode_currentLabelNotWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.MINUTE, "FRONT", true)));
        // 已闭合分钟 [14:40,14:41)：14:40:00（恰左沿）/14:40:35 两样本；进行中分钟 [14:41,14:42)：14:41:00（恰 fetchEnd）/14:41:05 两样本
        List<AsmRawRow> all = Arrays.asList(
                raw("2026-09-18T14:40:00Z", "26"),
                raw("2026-09-18T14:40:35Z", "23"),
                raw("2026-09-18T14:41:00Z", "20"),
                raw("2026-09-18T14:41:05Z", "20"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    OffsetDateTime start = inv.getArgument(2);
                    OffsetDateTime end = inv.getArgument(3);
                    List<AsmRawRow> hit = new ArrayList<>();
                    for (AsmRawRow r : all) {
                        if (!r.getDataTime().isBefore(start.toInstant()) && r.getDataTime().isBefore(end.toInstant())) {
                            hit.add(r);
                        }
                    }
                    return hit;
                });
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        Instant tick = Instant.parse("2026-09-18T14:41:10Z");
        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                tick.minusSeconds(120), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(1, rows.size(), "只写已闭合分钟 label 14:40；进行中 FRONT 分钟 label 14:41 不得落库: " + rows);
        AsmStatBucket closed = rows.get(0);
        assertAll("已闭合 FRONT 分钟 [14:40,14:41) 完整写（左沿样本含等收入）",
                () -> assertEquals(Instant.parse("2026-09-18T14:40:00Z"), closed.getDataTime(), "FRONT label=左沿"),
                () -> assertEquals(24.5, closed.getAvgValue(), 1e-9),
                () -> assertEquals(Long.valueOf(2), closed.getValidCount(), "2 样本全员（含 14:40:00 边界样本）"),
                () -> assertEquals(Long.valueOf(2), closed.getTotalCount()));
    }

    /**
     * 右沿闭合回归 ⑦（FRONT 5min 级联）：tick 14:36:30 不得计算进行中桶 [14:35,14:40)
     * （label=14:35，桶尾 14:40 &gt; now）——已就绪的 14:35/14:36 分钟子桶不进聚合；
     * 已闭合桶 [14:25,14:30)/[14:30,14:35) 以成员全集（5/5）写入。与回归 ⑤ 共同构成
     * 5min 粒度两 mode 产出层全覆盖。
     */
    @Test
    @SuppressWarnings("unchecked")
    void fiveMinUnfinished_frontMode_nextLabelNotWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.FIVE_MIN, "FRONT", true)));
        // 分钟子桶全集（FRONT 整分标注 14:25..14:36）：14:25..14:34 属已闭合 5min 桶；14:35/14:36 属进行中桶 [14:35,14:40)
        List<AsmStatBucket> children = new ArrayList<>();
        for (int m = 25; m <= 36; m++) {
            children.add(numChild(Instant.parse("2026-09-18T14:00:00Z").plusSeconds(m * 60L), 10.0));
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> hitFront(children, inv.getArgument(2), inv.getArgument(3)));
        when(statMapper.upsertFiveMin(any(List.class))).thenReturn(2);

        Instant tick = Instant.parse("2026-09-18T14:36:30Z");
        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                tick.minusSeconds(15 * 60), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertFiveMin(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(2, rows.size(), "只写已闭合 5min 桶 14:25/14:30；进行中 FRONT 桶 label 14:35 不得落库: " + rows);
        assertAll("已闭合 FRONT 5min 桶成员全集写",
                () -> assertEquals(Instant.parse("2026-09-18T14:25:00Z"), rows.get(0).getDataTime(),
                        "FRONT label=左沿 14:25"),
                () -> assertEquals(Long.valueOf(5), rows.get(0).getValidCount(), "14:25 桶 5/5 子桶"),
                () -> assertEquals(Long.valueOf(5), rows.get(0).getTotalCount()),
                () -> assertEquals(Instant.parse("2026-09-18T14:30:00Z"), rows.get(1).getDataTime(),
                        "FRONT label=左沿 14:30"),
                () -> assertEquals(Long.valueOf(5), rows.get(1).getValidCount(), "14:30 桶 5/5 子桶"),
                () -> assertEquals(Long.valueOf(5), rows.get(1).getTotalCount()));
    }

    /**
     * 右沿闭合回归 ⑧（恰边界保留侧 FRONT——回归 ④ 的镜像）：now=15:00:00 整点重算，
     * FRONT 小时 [14:00,15:00) 桶尾=label+interval=15:00 恰=now → 桶在该瞬时恰好闭合，
     * 必须写入。frontier=gridFloorHour(now)=15:00、WHERE 排等取子桶 14:00..14:59 全员；
     * 若收严为「桶尾 &lt; now」或 frontier 误多退一格，整点重算会静默丢掉刚闭合的小时——
     * 与回归 ④ 共同锁死两 mode 的恰边界语义。
     */
    @Test
    @SuppressWarnings("unchecked")
    void bucketEndExactlyNow_frontBucketKept() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.HOUR, "FRONT", true)));
        // 闭合小时 [14:00,15:00) 的 60 个分钟子桶（FRONT 整分标注 14:00..14:59）
        List<AsmStatBucket> children = new ArrayList<>();
        for (int m = 0; m < 60; m++) {
            children.add(numChild(Instant.parse("2026-09-18T14:00:00Z").plusSeconds(m * 60L), 10.0));
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(inv -> hitFront(children, inv.getArgument(2), inv.getArgument(3)));
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        Instant tick = Instant.parse("2026-09-18T15:00:00Z");
        engine.materializeGranularity(AsmStatGranularity.HOUR,
                tick.minusSeconds(2 * 3600), tick, "SCHEDULE", tick);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertHour(cap.capture());
        List<AsmStatBucket> rows = cap.getValue();
        assertEquals(1, rows.size(), "桶尾==now 的恰闭合 FRONT 小时桶须写入: " + rows);
        AsmStatBucket closed = rows.get(0);
        assertAll("恰边界（桶尾==now）保留并完整写",
                () -> assertEquals(Instant.parse("2026-09-18T14:00:00Z"), closed.getDataTime(),
                        "FRONT label=左沿 14:00（桶尾 15:00 == now）"),
                () -> assertEquals(Long.valueOf(60), closed.getValidCount(), "60 有效分钟子桶全员"),
                () -> assertEquals(Long.valueOf(60), closed.getTotalCount()));
    }

    /**
     * 空窗保护：窗整体在 now 之后（手动误配/时钟偏移形态）——frontier=gridFloor(now) 早于
     * fetchStart 时 fetch 条件空集，引擎不得抛异常/不得 upsert，干净返 0 并写 SUCCESS 审计
     * bucketCount=0（退化窗是空结果不是错误）。SQL 参数按计算值原样下发（fetchEnd &lt; fetchStart
     * 的倒挂形态不特判），空集由 WHERE 语义承载。
     */
    @Test
    @SuppressWarnings("unchecked")
    void windowStartsAfterNow_emptyFetchReturnsZero() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(AsmGranularityMask.MINUTE, "BACK", true)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // now=14:30、窗 [14:40,14:42]：fetchStart=14:39 > fetchEnd=gridFloor(now)=14:30（钳制后倒挂）
        int total = engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-18T14:40:00Z"), Instant.parse("2026-09-18T14:42:00Z"),
                "MANUAL", Instant.parse("2026-09-18T14:30:00Z"));

        assertAll("退化窗（frontier < fetchStart）干净返 0",
                () -> assertEquals(0, total, "空 fetch 返 0 不抛"),
                () -> verify(statMapper, never()).upsertMinute(any(List.class)));
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                args.capture(), args.capture(), args.capture(), args.capture());
        assertAll("倒挂参数原样下发（引擎零特判）",
                () -> assertEquals(Instant.parse("2026-09-18T14:39:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(0)).toInstant(), "fetchStart=floor(ws)-pad"),
                () -> assertEquals(Instant.parse("2026-09-18T14:30:00Z"),
                        ((OffsetDateTime) args.getAllValues().get(1)).toInstant(),
                        "fetchEnd=gridFloor(now)（钳制产物原样进 WHERE，空集语义由 DB 承载）"));
        ArgumentCaptor<AsmStatComputeLog> logCap = ArgumentCaptor.forClass(AsmStatComputeLog.class);
        verify(computeLogMapper).insert(logCap.capture());
        assertAll("空产出仍写 SUCCESS 审计",
                () -> assertEquals("SUCCESS", logCap.getValue().getStatus()),
                () -> assertEquals(Integer.valueOf(0), logCap.getValue().getBucketCount()));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<AsmStatBucket>> bucketCaptor() {
        return ArgumentCaptor.forClass((Class<List<AsmStatBucket>>) (Class<?>) List.class);
    }
}
