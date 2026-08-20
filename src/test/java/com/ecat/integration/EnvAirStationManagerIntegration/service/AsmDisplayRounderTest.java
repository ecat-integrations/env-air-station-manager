package com.ecat.integration.EnvAirStationManagerIntegration.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AsmDisplayRounder 单测——HALF_EVEN 边界 / null / 负数 / 精度来源缺失走默认。
 */
class AsmDisplayRounderTest {

    @Test
    void round_userReportedLongDecimal_truncatedTo2ByDefault() {
        // 用户实测：累积粉尘 160.67183333333332ug/m3 → 160.67
        assertEquals(160.67, AsmDisplayRounder.round(160.67183333333332d, null));
        assertEquals(160.67, AsmDisplayRounder.round(160.67183333333332d, -1));
    }

    @Test
    void round_halfEven_tiesToEvenNeighbor() {
        // 0.125 @2 → 0.12（偶）；0.135 @2 → 0.14（偶）——银行家舍入
        assertEquals(0.12, AsmDisplayRounder.round(0.125d, 2));
        assertEquals(0.14, AsmDisplayRounder.round(0.135d, 2));
    }

    @Test
    void round_negativeAndNullValue() {
        assertNull(AsmDisplayRounder.round(null, 2));
        assertEquals(-1.24, AsmDisplayRounder.round(-1.235d, 2));
    }

    @Test
    void round_defPrecisionHonored() {
        assertEquals(25.6, AsmDisplayRounder.round(25.555d, 1));
        assertEquals(25.0, AsmDisplayRounder.round(25.4d, 0));
    }
}
