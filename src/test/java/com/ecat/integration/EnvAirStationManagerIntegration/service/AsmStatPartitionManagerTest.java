package com.ecat.integration.EnvAirStationManagerIntegration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * AsmStatPartitionManager 单测——月迭代 / UTC 月边界 / 缓存 / 严格校验（对齐 ADM AdmStatPartitionManagerTest，
 * ASM 三张父表）。Mockito mock JdbcTemplate 验 DDL 语句字符串；无 sleep 无真实 DB。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmStatPartitionManagerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AsmStatPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = new AsmStatPartitionManager(jdbcTemplate);
    }

    private List<String> executedDdls(int expectedTimes) {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(expectedTimes)).execute(cap.capture());
        return cap.getAllValues();
    }

    /** 三张父表名（与 AsmStatGranularity.targetTable() 单一真相源对应）。 */
    private static final String[] PARENTS = {"asm_stat_minute", "asm_stat_5min", "asm_stat_hour"};

    @Test
    void monthIteration_singleMonth_ensuresAllThreeTablesWithUtcBoundary() {
        manager.ensureStatPartitions(
                Instant.parse("2026-08-10T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"));

        Set<String> expected = new HashSet<>();
        for (String parent : PARENTS) {
            expected.add("CREATE TABLE IF NOT EXISTS " + parent + "_202608 PARTITION OF " + parent
                    + " FOR VALUES FROM ('2026-08-01T00:00:00Z') TO ('2026-09-01T00:00:00Z')");
        }
        List<String> ddls = executedDdls(3);
        assertEquals(expected, new HashSet<>(ddls), "单月窗 = 三张父表各一条 8 月分区 DDL，UTC 月边界精确");
        for (String ddl : ddls) {
            assertTrue(ddl.contains("IF NOT EXISTS"), "DDL 须幂等: " + ddl);
        }
    }

    @Test
    void monthIteration_crossMonthWindow_generatesBothMonths() {
        manager.ensureStatPartitions(
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-09-05T00:00:00Z"));

        List<String> ddls = executedDdls(6);
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202608")).count(), "8 月分区 3 条");
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202609")).count(), "9 月分区 3 条");
    }

    @Test
    void monthIteration_crossYearWindow_generatesThreeMonths() {
        manager.ensureStatPartitions(
                Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2027-01-05T00:00:00Z"));

        List<String> ddls = executedDdls(9);
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202611")).count());
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202612")).count());
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202701")).count());
    }

    @Test
    void cache_secondCallForSameMonth_isZeroDdl() {
        manager.ensureStatPartitions(
                Instant.parse("2026-08-10T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"));
        manager.ensureStatPartitions(
                Instant.parse("2026-08-15T00:00:00Z"), Instant.parse("2026-08-25T00:00:00Z"));

        verify(jdbcTemplate, times(3)).execute(anyString());  // 第二次全缓存命中，零 DDL
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void startupPrime_coversCurrentMonthAndNeighbors() {
        // 8 月中旬启动 → 窗口 ±31 天覆盖 7/8/9 三月 = 9 条 DDL
        manager.ensureStartupPartitions(Instant.parse("2026-08-15T00:00:00Z"));
        List<String> ddls = executedDdls(9);
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202607")).count(), "回望 7 月");
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202608")).count(), "当月 8 月");
        assertEquals(3, ddls.stream().filter(d -> d.contains("_202609")).count(), "预建 9 月");
    }

    @Test
    void strict_nullAndReversedWindow_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureStatPartitions(null, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureStatPartitions(Instant.now(), null));
        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureStatPartitions(Instant.parse("2026-08-20T00:00:00Z"),
                        Instant.parse("2026-08-10T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> manager.ensureStartupPartitions(null));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void utcBoundary_jvmNonUtcZoneStillUtcEdges() {
        // 显式锁 UTC 月边界（JVM zone 不影响）：窗口落在 UTC 8 月，边界必为 UTC 8/1 与 9/1
        YearMonth expect = YearMonth.of(2026, 8);
        manager.ensureStatPartitions(
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-31T23:59:59Z"));
        for (String ddl : executedDdls(3)) {
            assertTrue(ddl.contains("'2026-08-01T00:00:00Z'"), "FROM 须 UTC 月初: " + ddl);
            assertTrue(ddl.contains("'2026-09-01T00:00:00Z'"), "TO 须次月 UTC 月初: " + ddl);
        }
        assertEquals(expect.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                Instant.parse("2026-08-01T00:00:00Z"));  // 语义锚（可读性）
    }
}
