package com.ecat.integration.EnvAirStationManagerIntegration.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AsmIntervalMode 单测——code 编码 / fromCode·of 解码 / 非法值严格抛。
 *
 * <p>扁平 @Test 非 @Nested（本模块 surefire 2.22.2 不发现纯 @Nested，同 ADM 教训）。</p>
 *
 * @author coffee
 */
class AsmIntervalModeTest {

    @Test
    void code_frontIsOneBackIsTwo() {
        assertEquals(1, AsmIntervalMode.FRONT.code());
        assertEquals(2, AsmIntervalMode.BACK.code());
    }

    @Test
    void fromCode_roundTrip() {
        assertEquals(AsmIntervalMode.FRONT, AsmIntervalMode.fromCode(1));
        assertEquals(AsmIntervalMode.BACK, AsmIntervalMode.fromCode(2));
    }

    @Test
    void fromCode_illegalCodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> AsmIntervalMode.fromCode(0));
        assertThrows(IllegalArgumentException.class, () -> AsmIntervalMode.fromCode(3));
    }

    @Test
    void of_nameParses_nullThrows() {
        assertEquals(AsmIntervalMode.FRONT, AsmIntervalMode.of("FRONT"));
        assertEquals(AsmIntervalMode.BACK, AsmIntervalMode.of("BACK"));
        assertThrows(IllegalArgumentException.class, () -> AsmIntervalMode.of(null));
        assertThrows(IllegalArgumentException.class, () -> AsmIntervalMode.of("LEFT"));
    }
}
