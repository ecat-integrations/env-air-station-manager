package com.ecat.integration.EnvAirStationManagerIntegration.support;

import java.time.Instant;

/**
 * ASM stat 桶网格切分——样本/子桶时刻 → 桶标注的单一算术源（机制同 ADM AdmStatGridBucketing，ASM 自建）。
 *
 * <p>应用场景：avg-only 聚合引擎按粒度网格分桶。网格锚 UTC epoch（秒空间），FRONT 桶标=左沿
 * （floor 到网格）、BACK 桶标=右沿（floor 后 +1 桶宽；样本恰落网格点时样本即右沿、归自身桶）。
 * 与引擎 fetch WHERE 开闭（FRONT {@code >= ? and < ?} / BACK {@code > ? and <= ?}）镜像一致——
 * 同一时刻在「取数边界」与「分桶归属」两处判定结果恒等，不重不漏。</p>
 *
 * <p>三粒度桶宽（1/5/60 分钟）均整除 epoch 秒网格，恒对齐；带纳秒尾的样本按其在时间线上的位置
 * 归桶（FRONT 不受纳秒影响、BACK 非整点样本进下一右沿桶）。</p>
 *
 * @author coffee
 */
public final class AsmStatGridBucketing {

    private AsmStatGridBucketing() {
    }

    /**
     * 时刻 → 该粒度该 mode 下的桶标注。
     *
     * @param t          样本/子桶时刻（UTC instant）
     * @param granularity 粒度（提供桶宽网格）
     * @param mode       区间模式（FRONT 左沿 / BACK 右沿）
     * @return 桶标注 Instant（网格对齐）
     */
    public static Instant truncateToGrid(Instant t, AsmStatGranularity granularity, AsmIntervalMode mode) {
        if (t == null) {
            throw new IllegalArgumentException("truncateToGrid t 不可为 null");
        }
        long intervalSec = granularity.interval().getSeconds();
        long epochSec = t.getEpochSecond();
        long mod = Math.floorMod(epochSec, intervalSec);
        if (mode == AsmIntervalMode.FRONT) {
            // 左沿：floor 到网格（纳秒不进位——桶内任意时刻同标）
            return Instant.ofEpochSecond(epochSec - mod);
        }
        // BACK 右沿：恰落网格点（整秒无纳秒尾）样本即右沿归自身桶；否则 floor +1 桶宽
        if (mod == 0 && t.getNano() == 0) {
            return Instant.ofEpochSecond(epochSec);
        }
        return Instant.ofEpochSecond(epochSec - mod + intervalSec);
    }
}
