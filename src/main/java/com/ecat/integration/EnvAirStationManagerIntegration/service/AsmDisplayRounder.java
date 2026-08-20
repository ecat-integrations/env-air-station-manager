package com.ecat.integration.EnvAirStationManagerIntegration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 读出口数值展示修约（HALF_EVEN）——snapshot/SSE 出口共用。
 *
 * <p>应用场景：存储铁律「存原生值」，live/raw 值经单位换算后常带全精度小数
 * （如 160.67183333333332），直接出口到总览瓦片/抽屉不可读。修约只发生在
 * 读出口（snapshot REST 与 SSE 帧），不落库、不改存储值。</p>
 *
 * <p>精度来源 = attr def {@code displayPrecision}；def 缺席或精度非法（&lt;0）时走
 * {@link #DEFAULT_DISPLAY_PRECISION}（用户实测反馈定案：累积粉尘 160.67 → 默认 2 位）。
 * 舍入模式 HALF_EVEN（银行家舍入），与 ADM AdmStatPolicy / core displayValue 口径一致。</p>
 *
 * @author coffee
 */
public final class AsmDisplayRounder {

    /** def 缺席/精度非法时的默认展示小数位。 */
    public static final int DEFAULT_DISPLAY_PRECISION = 2;

    private AsmDisplayRounder() {
    }

    /**
     * HALF_EVEN 展示修约。
     *
     * @param value     出口换算后的数值；null 原样返 null（非数值/text 属性）
     * @param precision 展示小数位；null 或 &lt;0 走 {@link #DEFAULT_DISPLAY_PRECISION}
     * @return 修约后的 Double；value 为 null 时 null
     */
    public static Double round(Double value, Integer precision) {
        if (value == null) {
            return null;
        }
        int scale = precision != null && precision >= 0 ? precision : DEFAULT_DISPLAY_PRECISION;
        return BigDecimal.valueOf(value)
                .setScale(scale, RoundingMode.HALF_EVEN)
                .doubleValue();
    }
}
