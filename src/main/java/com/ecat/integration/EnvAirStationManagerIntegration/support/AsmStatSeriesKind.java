package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * ASM 统计 series 的聚合语义类别（数值/非数值分流判据）。
 *
 * <p>2026-09-08 用户裁定放开 16 个非数值 bind attr 进统计（此前 avg-only 引擎只物化 numeric），
 * 且两类非数值量口径各自独立明确：</p>
 * <ul>
 *   <li>{@link #ALARM} 报警类——关注「期间是否发生过报警」：minute 小窗内任一样本=alarm 即 alarm
 *       （OR 语义）、hour 全窗 OR、5min 对齐报表口径取桶标时刻点采样；值域 normal/alarm。</li>
 *   <li>{@link #STATE} 状态/控制类——关注「该时刻设备处于什么状态」：minute 取距桶标时刻最近的
 *       实时样本、5min/hour 取桶标时刻点采样（hour 即整点行）。</li>
 * </ul>
 *
 * <p>{@link #AVG} 数值均值是既有语义（numeric attr 自动归属）；{@link #NONE} 非统计对象
 * （计算属性/共享状态/命令/事件快照/用户裁定剔除的控制属性）。</p>
 *
 * @author coffee
 */
public enum AsmStatSeriesKind {

    /** 数值均值（既有 avg-only 语义：numeric bind attr 自动归属，窗口均值 + 加权级联）。 */
    AVG,

    /** 报警类（值域 normal/alarm）：minute 小窗 OR / hour 全窗 OR / 5min 桶标时刻点采样。 */
    ALARM,

    /** 状态/控制类：取时刻状态——minute 距桶标最近样本 / 5min、hour 桶标时刻点采样。 */
    STATE,

    /** 非统计对象（不 seed、不物化）：默认归属，白名单外的 attrId 一律 NONE。 */
    NONE
}
