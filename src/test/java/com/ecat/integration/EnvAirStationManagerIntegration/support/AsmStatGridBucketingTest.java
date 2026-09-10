package com.ecat.integration.EnvAirStationManagerIntegration.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 桶切分边界归属单测——FRONT [S,E) 桶标=左沿 / BACK (L,R] 桶标=右沿（同 ADM 口径，ASM 自建）。
 * 锁死边界点归属：整点采样 FRONT 归左沿桶、BACK 归自身右沿桶，无歧义不重不漏。
 *
 * @author coffee
 */
class AsmStatGridBucketingTest {

    private static final Instant T = Instant.parse("2026-08-18T10:01:30Z");

    @Test
    void front_boundarySample_belongsToLeftEdgeBucket() {
        // 10:01:00 整点：FRONT [10:01,10:02) 左沿桶标 10:01
        assertEquals(Instant.parse("2026-08-18T10:01:00Z"),
                AsmStatGridBucketing.truncateToGrid(Instant.parse("2026-08-18T10:01:00Z"),
                        AsmStatGranularity.MINUTE, AsmIntervalMode.FRONT));
        // 桶内尾沿前一刻 10:01:59.999 仍归 10:01
        assertEquals(Instant.parse("2026-08-18T10:01:00Z"),
                AsmStatGridBucketing.truncateToGrid(Instant.parse("2026-08-18T10:01:59.999Z"),
                        AsmStatGranularity.MINUTE, AsmIntervalMode.FRONT));
        // 桶尾沿 10:02:00 整点归下一桶（[S,E) 右开）
        assertEquals(Instant.parse("2026-08-18T10:02:00Z"),
                AsmStatGridBucketing.truncateToGrid(Instant.parse("2026-08-18T10:02:00Z"),
                        AsmStatGranularity.MINUTE, AsmIntervalMode.FRONT));
    }

    @Test
    void back_boundarySample_belongsToRightEdgeBucket() {
        // 10:01:00 整点：BACK (10:00,10:01] 右沿桶标 10:01
        assertEquals(Instant.parse("2026-08-18T10:01:00Z"),
                AsmStatGridBucketing.truncateToGrid(Instant.parse("2026-08-18T10:01:00Z"),
                        AsmStatGranularity.MINUTE, AsmIntervalMode.BACK));
        // 10:01:00.001 归下一右沿桶 10:02
        assertEquals(Instant.parse("2026-08-18T10:02:00Z"),
                AsmStatGridBucketing.truncateToGrid(Instant.parse("2026-08-18T10:01:00.001Z"),
                        AsmStatGranularity.MINUTE, AsmIntervalMode.BACK));
        // 10:01:30 归右沿桶 10:02
        assertEquals(Instant.parse("2026-08-18T10:02:00Z"),
                AsmStatGridBucketing.truncateToGrid(T, AsmStatGranularity.MINUTE, AsmIntervalMode.BACK));
    }

    @Test
    void fiveMinAndHourGrids_alignToGranularityInterval() {
        // 5min 网格锚 UTC 整点（10:00/10:05/...）：10:01:30 FRONT→10:00 / BACK→10:05
        assertEquals(Instant.parse("2026-08-18T10:00:00Z"),
                AsmStatGridBucketing.truncateToGrid(T, AsmStatGranularity.FIVE_MIN, AsmIntervalMode.FRONT));
        assertEquals(Instant.parse("2026-08-18T10:05:00Z"),
                AsmStatGridBucketing.truncateToGrid(T, AsmStatGranularity.FIVE_MIN, AsmIntervalMode.BACK));
        // hour：10:01:30 FRONT→10:00 / BACK→11:00
        assertEquals(Instant.parse("2026-08-18T10:00:00Z"),
                AsmStatGridBucketing.truncateToGrid(T, AsmStatGranularity.HOUR, AsmIntervalMode.FRONT));
        assertEquals(Instant.parse("2026-08-18T11:00:00Z"),
                AsmStatGridBucketing.truncateToGrid(T, AsmStatGranularity.HOUR, AsmIntervalMode.BACK));
    }
}
