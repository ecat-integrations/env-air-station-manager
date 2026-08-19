package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmStatComputeLogMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmGranularityMask;
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
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 引擎类型转换辅助（toInstant/toBigDecimal/toNullableDouble，AsmStatAggregationEngine 内
 * JdbcTemplate RowMapper lambda）的全分支覆盖——jacoco 实测这组分支 0 覆盖（mock 直接返已typed
 * 对象绕过转换），但 PG 驱动实际会返 OffsetDateTime / 某些路径返 Timestamp，转换错即静默错数据，
 * 属真缺口。经捕获 RowMapper + mock ResultSet 逐类型驱动（不连 DB）。
 */
@ExtendWith(MockitoExtension.class)
class AsmStatAggregationEngineTypeConversionTest {

    private static final String UID = "logicdevice_station.th";
    private static final String ATTR = "temperature";

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

    /** 触发一次 minute 物化并捕获传给 jdbcTemplate 的 raw RowMapper（转换逻辑所在 lambda）。 */
    @SuppressWarnings("unchecked")
    private RowMapper<AsmRawRow> captureRawRowMapper(Instant t) {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                AsmConfigStat.builder().logicDeviceUniqueId(UID).attrId(ATTR)
                        .enabled(true).granularityMask(AsmGranularityMask.ALL)
                        .materializationMode("FRONT").build()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        org.mockito.Mockito.lenient().when(statMapper.upsertMinute(any())).thenReturn(0);
        engine.materializeGranularity(AsmStatGranularity.MINUTE, t.minusSeconds(120), t, "SCHEDULE", t);
        ArgumentCaptor<RowMapper> cap = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), cap.capture(), any(), any(), any(), any());
        return cap.getValue();
    }

    private static ResultSet rs(Object dataTime, Object valueNum) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        org.mockito.Mockito.lenient().when(rs.getObject("data_time")).thenReturn(dataTime);
        org.mockito.Mockito.lenient().when(rs.getObject("value_num")).thenReturn(valueNum);
        return rs;
    }

    @Test
    void rawRowMapper_convertsOffsetDateTimeAndBigDecimal() throws Exception {
        RowMapper<AsmRawRow> m = captureRawRowMapper(Instant.parse("2026-08-18T10:10:00Z"));
        OffsetDateTime odt = OffsetDateTime.of(2026, 8, 18, 10, 8, 5, 0, ZoneOffset.UTC);
        AsmRawRow row = m.mapRow(rs(odt, new BigDecimal("1.5")), 0);
        assertEquals(Instant.parse("2026-08-18T10:08:05Z"), row.getDataTime());
        assertEquals(0, new BigDecimal("1.5").compareTo(row.getValueNum()));
    }

    @Test
    void rawRowMapper_convertsTimestampAndNumberValue() throws Exception {
        RowMapper<AsmRawRow> m = captureRawRowMapper(Instant.parse("2026-08-18T10:10:00Z"));
        Timestamp ts = Timestamp.from(Instant.parse("2026-08-18T10:08:05Z"));
        AsmRawRow row = m.mapRow(rs(ts, 2.5d), 0);
        assertEquals(Instant.parse("2026-08-18T10:08:05Z"), row.getDataTime());
        assertEquals(0, BigDecimal.valueOf(2.5d).compareTo(row.getValueNum()));
    }

    @Test
    void rawRowMapper_convertsInstantDirectly() throws Exception {
        RowMapper<AsmRawRow> m = captureRawRowMapper(Instant.parse("2026-08-18T10:10:00Z"));
        AsmRawRow row = m.mapRow(rs(Instant.parse("2026-08-18T10:08:05Z"), null), 0);
        assertEquals(Instant.parse("2026-08-18T10:08:05Z"), row.getDataTime());
        assertEquals(null, row.getValueNum());
    }

    @Test
    void rawRowMapper_rejectsUnknownTimeType_strictMode() throws Exception {
        RowMapper<AsmRawRow> m = captureRawRowMapper(Instant.parse("2026-08-18T10:10:00Z"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> m.mapRow(rs("2026-08-18 10:08:05", null), 0));
        assertEquals(true, ex.getMessage().contains("data_time 列非"));
    }

    @Test
    void rawRowMapper_rejectsUnknownValueType_strictMode() throws Exception {
        RowMapper<AsmRawRow> m = captureRawRowMapper(Instant.parse("2026-08-18T10:10:00Z"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> m.mapRow(rs(Instant.parse("2026-08-18T10:08:05Z"), "3.0"), 0));
        assertEquals(true, ex.getMessage().contains("value_num 非 Number"));
    }

    /** 级联取数 RowMapper（fetchChildBuckets）：avg_value 走 toNullableDouble 分支。 */
    @Test
    @SuppressWarnings("unchecked")
    void cascadeRowMapper_convertsTypesAndRejectsUnknown() throws Exception {
        when(configStatMapper.selectAll()).thenReturn(Collections.singletonList(
                AsmConfigStat.builder().logicDeviceUniqueId(UID).attrId(ATTR)
                        .enabled(true).granularityMask(AsmGranularityMask.ALL)
                        .materializationMode("FRONT").build()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        org.mockito.Mockito.lenient().when(statMapper.upsertFiveMin(any())).thenReturn(0);
        engine.materializeGranularity(AsmStatGranularity.FIVE_MIN,
                Instant.parse("2026-08-18T10:05:00Z"), Instant.parse("2026-08-18T10:10:00Z"),
                "SCHEDULE", Instant.parse("2026-08-18T10:10:00Z"));
        ArgumentCaptor<RowMapper> cap = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), cap.capture(), any(), any(), any(), any());
        RowMapper<AsmStatBucket> m = cap.getValue();

        OffsetDateTime odt = OffsetDateTime.of(2026, 8, 18, 10, 5, 0, 0, ZoneOffset.UTC);
        ResultSet rs = mock(ResultSet.class);
        org.mockito.Mockito.lenient().when(rs.getObject("data_time")).thenReturn(odt);
        org.mockito.Mockito.lenient().when(rs.getObject("avg_value")).thenReturn(1.25f);
        org.mockito.Mockito.lenient().when(rs.getLong("valid_count")).thenReturn(3L);
        org.mockito.Mockito.lenient().when(rs.getLong("total_count")).thenReturn(4L);
        AsmStatBucket b = m.mapRow(rs, 0);
        assertEquals(Instant.parse("2026-08-18T10:05:00Z"), b.getDataTime());
        assertEquals(1.25d, b.getAvgValue());
        assertEquals(3L, b.getValidCount());
        assertEquals(4L, b.getTotalCount());

        org.mockito.Mockito.lenient().when(rs.getObject("avg_value")).thenReturn("bad");
        assertThrows(IllegalStateException.class, () -> m.mapRow(rs, 1));
    }
}
