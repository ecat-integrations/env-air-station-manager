package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * ASM 物化粒度位掩码——{@code asm_config_stat.granularity_mask} smallint 列的编解码单一真相源。
 *
 * <p>应用场景：每 series 可独立开关 minute/5min/hour 三粒度物化（bit0=minute / bit1=5min / bit2=hour）；
 * 物化引擎按 {@link #isApplicable(int, AsmStatGranularity)} 在 app 内存过滤 series（mask 位未开的粒度跳过）。
 * seed 默认 {@link #ALL}（7，三粒度全开）。</p>
 *
 * @author coffee
 */
public final class AsmGranularityMask {

    /** bit0：分钟。 */
    public static final int MINUTE = 1;
    /** bit1：5 分钟。 */
    public static final int FIVE_MIN = 2;
    /** bit2：小时。 */
    public static final int HOUR = 4;
    /** seed 默认全开（minute|5min|hour）。 */
    public static final int ALL = MINUTE | FIVE_MIN | HOUR;

    private AsmGranularityMask() {
    }

    /** 掩码中该粒度位是否开启（engine 过滤入口）。 */
    public static boolean isApplicable(int mask, AsmStatGranularity granularity) {
        return (mask & granularity.mask()) != 0;
    }

    /**
     * 校验/规范化解码：掩码只许由已知位组成（未知位=脏数据，抛不臆测忽略）。
     *
     * @return 原样返回合法掩码（调用链断言用）
     * @throws IllegalArgumentException 掩码 &lt;=0 或含未知位（&gt;7）
     */
    public static int validate(int mask) {
        if (mask <= 0 || mask > ALL) {
            throw new IllegalArgumentException("非法 granularity_mask: " + mask + "（合法位组合 1..7：bit0=minute/bit1=5min/bit2=hour）");
        }
        return mask;
    }
}
