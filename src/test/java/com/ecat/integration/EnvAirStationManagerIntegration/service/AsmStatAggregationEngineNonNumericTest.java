package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatBucket;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 引擎非数值（ALARM/STATE）聚合语义单测——series 按 attrId 分流后的各自口径：
 * ALARM minute 小窗 OR / STATE minute 距桶标最近样本（等距取新）/ 5min、hour 点采样桶标对齐
 * （FRONT 左沿 / BACK 右沿）/ hour ALARM 全窗 OR 防漏报（00 分行缺失仍出 alarm）/ 计数四形态
 * （非数值 minute 非空文本计数、点采样继承、hour OR 求和、数值 AVG 不变）/ avgValue-valueText
 * 互斥 / FRONT、BACK 双 mode 独立成行。JdbcTemplate mock 取数（聚合全在 Java），无 DB 无 sleep。
 */
@ExtendWith(MockitoExtension.class)
class AsmStatAggregationEngineNonNumericTest {

    private static final String UID = "logicdevice_station.security";
    private static final String ALARM_ATTR = "ir_alarm";
    private static final String STATE_ATTR = "power_status";
    private static final String NUMERIC_ATTR = "temperature";
    private static final Instant NOW = Instant.parse("2026-09-08T10:10:00Z");

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

    private static AsmConfigStat config(String attrId, Integer mask, String mode) {
        return AsmConfigStat.builder()
                .logicDeviceUniqueId(UID).attrId(attrId)
                .enabled(true).granularityMask(mask).materializationMode(mode).build();
    }

    /** 非数值 raw 样本（valueNum 恒 null——非数值 series 无数值槽）。 */
    private static AsmRawRow text(String time, String valueText) {
        return AsmRawRow.of(Instant.parse(time), null, valueText);
    }

    /** minute 子桶行（非数值形态：valueText + 计数）。 */
    private static AsmStatBucket minuteRow(String time, String valueText, long valid, long total) {
        return AsmStatBucket.builder().dataTime(Instant.parse(time))
                .valueText(valueText).validCount(valid).totalCount(total).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void alarmMinute_anyAlarmSampleWins_countsNonEmptyTextOnly() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(ALARM_ATTR, AsmGranularityMask.MINUTE, "FRONT")));
        // 10:08 桶混合：normal + alarm + null 文本（valid 只计非空文本）→ OR 出 alarm；10:09 桶全 normal
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                text("2026-09-08T10:08:10Z", "normal"),
                text("2026-09-08T10:08:20Z", "alarm"),
                text("2026-09-08T10:08:30Z", null),
                text("2026-09-08T10:09:10Z", "normal")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(2);

        int total = engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:10:00Z"), "SCHEDULE", NOW);

        assertEquals(2, total);
        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        List<AsmStatBucket> buckets = cap.getValue();
        AsmStatBucket b0 = buckets.get(0);
        assertEquals(Instant.parse("2026-09-08T10:08:00Z"), b0.getDataTime());
        assertEquals("alarm", b0.getValueText(), "窗口任一 alarm 样本 → alarm（小窗 OR）");
        assertEquals(Long.valueOf(2), b0.getValidCount(), "valid=窗口内非空文本样本数（null 文本不计）");
        assertEquals(Long.valueOf(3), b0.getTotalCount(), "total=窗口全部样本数");
        assertNull(b0.getAvgValue(), "非数值行 avgValue=null（互斥不变式）");
        assertEquals(Integer.valueOf(AsmIntervalMode.FRONT.code()), b0.getIntervalMode());
        AsmStatBucket b1 = buckets.get(1);
        assertEquals("normal", b1.getValueText(), "全 normal 桶 → normal");
        assertEquals(Long.valueOf(1), b1.getValidCount());
        assertEquals(Long.valueOf(1), b1.getTotalCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void alarmMinute_emptyWindow_noRowWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(ALARM_ATTR, AsmGranularityMask.MINUTE, "FRONT")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        int total = engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:10:00Z"), "SCHEDULE", NOW);

        assertEquals(0, total);
        verify(statMapper, never()).upsertMinute(any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateMinute_takesSampleNearestToBucketLabel() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.MINUTE, "FRONT")));
        // FRONT 桶标=左沿 10:08：10:08:10 距标 10s 胜 10:08:50 的 50s（前标偏早样本）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                text("2026-09-08T10:08:10Z", "heating"),
                text("2026-09-08T10:08:50Z", "cooling")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:09:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        AsmStatBucket b = cap.getValue().get(0);
        assertEquals(Instant.parse("2026-09-08T10:08:00Z"), b.getDataTime());
        assertEquals("heating", b.getValueText(), "FRONT 取距左沿标最近样本");
        assertEquals(Long.valueOf(2), b.getValidCount());
        assertEquals(Long.valueOf(2), b.getTotalCount());
        assertNull(b.getAvgValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateMinute_backMode_takesSampleNearestToRightEdgeLabel() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.MINUTE, "BACK")));
        // BACK 桶标=右沿 10:09：10:08:50 距标 10s 胜 10:08:10 的 50s（后标偏晚样本）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                text("2026-09-08T10:08:10Z", "heating"),
                text("2026-09-08T10:08:50Z", "cooling")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:09:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        AsmStatBucket b = cap.getValue().get(0);
        assertEquals(Instant.parse("2026-09-08T10:09:00Z"), b.getDataTime(), "BACK 桶标=右沿");
        assertEquals("cooling", b.getValueText(), "BACK 取距右沿标最近样本");
    }

    /**
     * 「等距取 dataTime 更新者」的可达形态：分钟桶内样本恒在桶标同侧（FRONT 桶标=左沿、BACK=右沿），
     * |dataTime-桶标| 对不同 dataTime 严格单调——等距仅在<b>同一 dataTime 的重复 raw 行</b>时出现
     * （asm_data_sample 主键为 bigserial id，允许同刻多行），此时「更新者」不可再分。锁的风险：
     * 重复行不放大成多行、计数 total 计入全部行；值取自哪一行非生产保证（fetch 无 ORDER BY），
     * 只断言取值属于该时刻样本值域。
     */
    @Test
    @SuppressWarnings("unchecked")
    void stateMinute_duplicateDataTimeRows_collapseToSingleRow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.MINUTE, "FRONT")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                text("2026-09-08T10:08:20Z", "low"),
                text("2026-09-08T10:08:20Z", "high")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:09:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        List<AsmStatBucket> buckets = cap.getValue();
        assertEquals(1, buckets.size(), "同刻重复行仍归并为单桶单行");
        AsmStatBucket b = buckets.get(0);
        assertEquals(Long.valueOf(2), b.getValidCount());
        assertEquals(Long.valueOf(2), b.getTotalCount());
        assertTrue("low".equals(b.getValueText()) || "high".equals(b.getValueText()),
                "取值须属于该时刻样本值域之一，实际=" + b.getValueText());
    }

    /** |dataTime-桶标| 比较须亚秒精确：20.1s 与 20.9s 非同距（粗秒比较会退化为等距取后者）。 */
    @Test
    @SuppressWarnings("unchecked")
    void stateMinute_subSecondDistancePrecision_picksTrulyNearest() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.MINUTE, "FRONT")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                text("2026-09-08T10:08:20.900Z", "high"),
                text("2026-09-08T10:08:20.100Z", "low")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:09:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        assertEquals("low", cap.getValue().get(0).getValueText(), "距桶标 20.1s 胜 20.9s（亚秒精确比较）");
    }

    @Test
    void stateMinute_emptyWindow_noRowWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.MINUTE, "FRONT")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        int total = engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:09:00Z"), "SCHEDULE", NOW);

        assertEquals(0, total);
        verify(statMapper, never()).upsertMinute(any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void alarmFiveMin_frontMode_samplesMinuteRowAtLeftEdgeLabel() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(ALARM_ATTR, AsmGranularityMask.FIVE_MIN, "FRONT")));
        // FRONT 桶标 10:05：组内成员 10:05/10:07，点采样取 10:05 行（非 OR——10:07 的 alarm 不上卷）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:05:00Z", "normal", 2, 3),
                minuteRow("2026-09-08T10:07:00Z", "alarm", 1, 1)));
        when(statMapper.upsertFiveMin(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                Instant.parse("2026-09-08T10:05:00Z"), Instant.parse("2026-09-08T10:10:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertFiveMin(cap.capture());
        AsmStatBucket parent = cap.getValue().get(0);
        assertEquals(Instant.parse("2026-09-08T10:05:00Z"), parent.getDataTime(), "FRONT 桶标=左沿 10:05");
        assertEquals("normal", parent.getValueText(), "ALARM 5min 点采样：取桶标时刻行，非窗口 OR");
        assertEquals(Long.valueOf(2), parent.getValidCount(), "点采样继承 minute 行 valid");
        assertEquals(Long.valueOf(3), parent.getTotalCount(), "点采样继承 minute 行 total");
        assertNull(parent.getAvgValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void alarmFiveMin_backMode_samplesMinuteRowAtRightEdgeLabel() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(ALARM_ATTR, AsmGranularityMask.FIVE_MIN, "BACK")));
        // BACK 桶标 10:10：组内成员 10:06/10:10，点采样取 10:10 行（10:06 的 alarm 不上卷）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:06:00Z", "alarm", 1, 1),
                minuteRow("2026-09-08T10:10:00Z", "normal", 2, 2)));
        when(statMapper.upsertFiveMin(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                Instant.parse("2026-09-08T10:05:00Z"), Instant.parse("2026-09-08T10:10:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertFiveMin(cap.capture());
        AsmStatBucket parent = cap.getValue().get(0);
        assertEquals(Instant.parse("2026-09-08T10:10:00Z"), parent.getDataTime(), "BACK 桶标=右沿 10:10");
        assertEquals("normal", parent.getValueText(), "取 10:10 行值，10:06 的 alarm 属上一 BACK 桶");
        assertEquals(Long.valueOf(2), parent.getValidCount());
        assertEquals(Long.valueOf(2), parent.getTotalCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateFiveMin_labelMinuteRowMissing_noParentRow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.FIVE_MIN, "FRONT")));
        // FRONT 桶标 10:05，组内只有 10:06/10:07（10:05 行缺失）→ 缺行不写父行
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:06:00Z", "on", 1, 1),
                minuteRow("2026-09-08T10:07:00Z", "off", 1, 1)));

        int total = engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                Instant.parse("2026-09-08T10:05:00Z"), Instant.parse("2026-09-08T10:10:00Z"), "SCHEDULE", NOW);

        assertEquals(0, total);
        verify(statMapper, never()).upsertFiveMin(any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateFiveMin_backMode_inheritsLabelRowCounts() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.FIVE_MIN, "BACK")));
        // BACK 桶标 10:10：取 10:10 行并继承其计数（10:09 行仅供组内存在性，不上卷）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:09:00Z", "off", 1, 2),
                minuteRow("2026-09-08T10:10:00Z", "on", 4, 6)));
        when(statMapper.upsertFiveMin(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                Instant.parse("2026-09-08T10:05:00Z"), Instant.parse("2026-09-08T10:10:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertFiveMin(cap.capture());
        AsmStatBucket parent = cap.getValue().get(0);
        assertEquals(Instant.parse("2026-09-08T10:10:00Z"), parent.getDataTime());
        assertEquals("on", parent.getValueText());
        assertEquals(Long.valueOf(4), parent.getValidCount(), "继承 10:10 行 valid=4");
        assertEquals(Long.valueOf(6), parent.getTotalCount(), "继承 10:10 行 total=6");
    }

    @Test
    @SuppressWarnings("unchecked")
    void alarmHour_windowOrAcrossMinuteRows_withoutOnTheHourRow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(ALARM_ATTR, AsmGranularityMask.HOUR, "FRONT")));
        // 00 分行缺失、仅 01-59 分有行且 10:15 为 alarm → hour 仍出 alarm（全窗 OR 防漏报，非 00 分点采样）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:15:00Z", "alarm", 2, 2),
                minuteRow("2026-09-08T10:30:00Z", "normal", 3, 3),
                minuteRow("2026-09-08T10:59:00Z", "normal", 1, 1)));
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.HOUR,
                Instant.parse("2026-09-08T10:00:00Z"), Instant.parse("2026-09-08T11:00:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertHour(cap.capture());
        AsmStatBucket hour = cap.getValue().get(0);
        assertEquals(Instant.parse("2026-09-08T10:00:00Z"), hour.getDataTime());
        assertEquals("alarm", hour.getValueText(), "窗口任一 minute 行 alarm → alarm（缺 00 分行不漏报）");
        assertEquals(Long.valueOf(6), hour.getValidCount(), "OR 行 valid=成员 minute 行求和 2+3+1");
        assertEquals(Long.valueOf(6), hour.getTotalCount(), "OR 行 total=成员求和");
        assertNull(hour.getAvgValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void alarmHour_allNormalMinuteRows_normalRow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(ALARM_ATTR, AsmGranularityMask.HOUR, "FRONT")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:15:00Z", "normal", 1, 1),
                minuteRow("2026-09-08T10:45:00Z", "normal", 2, 2)));
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.HOUR,
                Instant.parse("2026-09-08T10:00:00Z"), Instant.parse("2026-09-08T11:00:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertHour(cap.capture());
        AsmStatBucket hour = cap.getValue().get(0);
        assertEquals("normal", hour.getValueText());
        assertEquals(Long.valueOf(3), hour.getValidCount());
        assertEquals(Long.valueOf(3), hour.getTotalCount());
    }

    @Test
    void alarmHour_noMinuteRows_noRowWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(ALARM_ATTR, AsmGranularityMask.HOUR, "FRONT")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        int total = engine.materializeGranularity(AsmStatGranularity.HOUR,
                Instant.parse("2026-09-08T10:00:00Z"), Instant.parse("2026-09-08T11:00:00Z"), "SCHEDULE", NOW);

        assertEquals(0, total);
        verify(statMapper, never()).upsertHour(any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateHour_samplesOnTheHourMinuteRow() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.HOUR, "FRONT")));
        // hour 桶标 10:00 即整点行：取 10:00 行 'auto'（10:30 行不上卷），计数继承
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:00:00Z", "auto", 3, 5),
                minuteRow("2026-09-08T10:30:00Z", "cooling", 9, 9)));
        when(statMapper.upsertHour(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.HOUR,
                Instant.parse("2026-09-08T10:00:00Z"), Instant.parse("2026-09-08T11:00:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertHour(cap.capture());
        AsmStatBucket hour = cap.getValue().get(0);
        assertEquals(Instant.parse("2026-09-08T10:00:00Z"), hour.getDataTime());
        assertEquals("auto", hour.getValueText(), "STATE hour=整点 00 分行点采样");
        assertEquals(Long.valueOf(3), hour.getValidCount(), "继承整点行 valid（非求和）");
        assertEquals(Long.valueOf(5), hour.getTotalCount(), "继承整点行 total");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateHour_onTheHourRowMissing_noRowWritten() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.HOUR, "FRONT")));
        // 窗口内只有 10:15/10:30 行、整点 10:00 行缺失 → 不写 hour 行
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(Arrays.asList(
                minuteRow("2026-09-08T10:15:00Z", "auto", 1, 1),
                minuteRow("2026-09-08T10:30:00Z", "cooling", 1, 1)));

        int total = engine.materializeGranularity(AsmStatGranularity.HOUR,
                Instant.parse("2026-09-08T10:00:00Z"), Instant.parse("2026-09-08T11:00:00Z"), "SCHEDULE", NOW);

        assertEquals(0, total);
        verify(statMapper, never()).upsertHour(any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonNumericBothModes_frontAndBackRowsWrittenIndependently() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(STATE_ATTR, AsmGranularityMask.MINUTE, "BOTH")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(text("2026-09-08T10:08:10Z", "on")));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:10:00Z"), "SCHEDULE", NOW);

        // BOTH 双趟各产独立行（PK 含 interval_mode）：FRONT 桶标 10:08 / BACK 桶标 10:09
        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper, times(2)).upsertMinute(cap.capture());
        List<AsmStatBucket> frontPass = cap.getAllValues().get(0);
        List<AsmStatBucket> backPass = cap.getAllValues().get(1);
        assertEquals(Integer.valueOf(AsmIntervalMode.FRONT.code()), frontPass.get(0).getIntervalMode());
        assertEquals(Instant.parse("2026-09-08T10:08:00Z"), frontPass.get(0).getDataTime());
        assertEquals(Integer.valueOf(AsmIntervalMode.BACK.code()), backPass.get(0).getIntervalMode());
        assertEquals(Instant.parse("2026-09-08T10:09:00Z"), backPass.get(0).getDataTime());
        assertEquals("on", backPass.get(0).getValueText());
    }

    /** 互斥不变式另一侧：数值 AVG 行 valueText 恒 null（分流不得给数值行写文本槽）。 */
    @Test
    @SuppressWarnings("unchecked")
    void numericAvgRow_valueTextStaysNull() {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                config(NUMERIC_ATTR, AsmGranularityMask.MINUTE, "FRONT")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(
                        AsmRawRow.of(Instant.parse("2026-09-08T10:08:10Z"), new BigDecimal("1.5"))));
        when(statMapper.upsertMinute(any(List.class))).thenReturn(1);

        engine.materializeGranularity(AsmStatGranularity.MINUTE,
                Instant.parse("2026-09-08T10:08:00Z"), Instant.parse("2026-09-08T10:09:00Z"), "SCHEDULE", NOW);

        ArgumentCaptor<List<AsmStatBucket>> cap = bucketCaptor();
        verify(statMapper).upsertMinute(cap.capture());
        AsmStatBucket b = cap.getValue().get(0);
        assertEquals(1.5, b.getAvgValue(), 1e-9);
        assertNull(b.getValueText(), "数值行 valueText=null（互斥不变式）");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<AsmStatBucket>> bucketCaptor() {
        return ArgumentCaptor.forClass((Class<List<AsmStatBucket>>) (Class<?>) List.class);
    }
}
