package com.ecat.integration.EnvAirStationManagerIntegration.support;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AsmMaterializationMode 单测——modes() 展开物化区间模式集合 / of() 严格解析。
 *
 * @author coffee
 */
class AsmMaterializationModeTest {

    @Test
    void modes_expandByScope() {
        assertEquals(EnumSet.of(AsmIntervalMode.FRONT), AsmMaterializationMode.FRONT.modes());
        assertEquals(EnumSet.of(AsmIntervalMode.BACK), AsmMaterializationMode.BACK.modes());
        assertEquals(EnumSet.allOf(AsmIntervalMode.class), AsmMaterializationMode.BOTH.modes());
    }

    @Test
    void of_strictParse() {
        assertEquals(AsmMaterializationMode.BOTH, AsmMaterializationMode.of("BOTH"));
        assertEquals(AsmMaterializationMode.FRONT, AsmMaterializationMode.of("FRONT"));
        assertEquals(AsmMaterializationMode.BACK, AsmMaterializationMode.of("BACK"));
        assertThrows(IllegalArgumentException.class, () -> AsmMaterializationMode.of(null));
        assertThrows(IllegalArgumentException.class, () -> AsmMaterializationMode.of("ALL"));
    }
}
