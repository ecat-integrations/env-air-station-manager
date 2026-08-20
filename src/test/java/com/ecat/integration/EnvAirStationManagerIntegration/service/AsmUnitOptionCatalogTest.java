package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmUnitOptionGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单位候选目录（单位设置抽屉）：同类组在前 + 气态源追加跨类浓度组；非气态源只有同类组。
 */
class AsmUnitOptionCatalogTest {

    @Test
    void gaseousSource_sameClassFirstThenCrossClassConcentration() {
        List<AsmUnitOptionGroup> groups = AsmUnitOptionCatalog.groupsFor("AirMassUnit.UGM3");
        assertEquals(2, groups.size());
        // 同类组在前：质量浓度全集（key=full key，可直接回传 PUT config-unit）
        assertEquals("质量浓度", groups.get(0).getClassLabel());
        assertEquals("AirMassUnit.UGM3", groups.get(0).getUnits().get(0).getKey());
        assertEquals("ug/m3", groups.get(0).getUnits().get(0).getSymbol());
        // 跨类组：体积浓度（ppm/ppb——站房域唯一可跨类换算域）
        assertEquals("体积浓度", groups.get(1).getClassLabel());
        assertTrue(groups.get(1).getUnits().stream().anyMatch(u -> "ppm".equals(u.getSymbol())));
    }

    @Test
    void reverseGaseousSource_alsoTwoGroups() {
        List<AsmUnitOptionGroup> groups = AsmUnitOptionCatalog.groupsFor("AirVolumeUnit.PPM");
        assertEquals(2, groups.size());
        assertEquals("体积浓度", groups.get(0).getClassLabel());
        assertEquals("质量浓度", groups.get(1).getClassLabel());
    }

    @Test
    void nonGaseousSource_onlySameClassGroup() {
        List<AsmUnitOptionGroup> groups = AsmUnitOptionCatalog.groupsFor("TemperatureUnit.CELSIUS");
        assertEquals(1, groups.size());
        assertEquals("温度", groups.get(0).getClassLabel());
        assertEquals("TemperatureUnit.CELSIUS", groups.get(0).getUnits().get(0).getKey());
    }

    @Test
    void nullOrDirtyKey_returnsNullNoCandidates() {
        assertNull(AsmUnitOptionCatalog.groupsFor(null));
        assertNull(AsmUnitOptionCatalog.groupsFor(""));
        assertNull(AsmUnitOptionCatalog.groupsFor("NoSuchUnit.X"));
    }
}
