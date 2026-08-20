package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigUnitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 配置读写收口：mask/mode 严格校验抛、STORAGE purpose 拒绝（seed 域）、
 * config_unit 写后必调 AsmUnitContract.invalidate（负结果缓存教训）、config_stat 不 invalidate。
 */
@ExtendWith(MockitoExtension.class)
class AsmConfigServiceTest {

    @Mock
    private AsmConfigStatMapper configStatMapper;
    @Mock
    private AsmConfigUnitMapper configUnitMapper;
    @Mock
    private AsmUnitContract unitContract;

    private AsmConfigService service;

    @BeforeEach
    void setUp() {
        service = new AsmConfigService(configStatMapper, configUnitMapper, unitContract);
    }

    @Test
    void updateStatConfig_validatesMaskStrictly() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatConfig("uid-1", "temperature", true, 8, "BOTH", "admin"));
        verifyNoInteractions(configStatMapper);
    }

    @Test
    void updateStatConfig_validatesModeStrictly() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatConfig("uid-1", "temperature", true, 7, "MIDDLE", "admin"));
        verifyNoInteractions(configStatMapper);
    }

    @Test
    void updateStatConfig_rejectsBlankSeries() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatConfig(" ", "temperature", true, 7, "BOTH", "admin"));
    }

    @Test
    void updateStatConfig_upsertsAndDoesNotInvalidateUnitCache() {
        AsmConfigStat saved = service.updateStatConfig("uid-1", "temperature", false, 3, "FRONT", "admin");

        ArgumentCaptor<AsmConfigStat> captor = ArgumentCaptor.forClass(AsmConfigStat.class);
        verify(configStatMapper).upsert(captor.capture());
        assertEquals("uid-1", captor.getValue().getLogicDeviceUniqueId());
        assertEquals("temperature", captor.getValue().getAttrId());
        assertEquals(false, captor.getValue().getEnabled());
        assertEquals(3, captor.getValue().getGranularityMask());
        assertEquals("FRONT", captor.getValue().getMaterializationMode());
        assertEquals(saved, captor.getValue());
        verifyNoInteractions(unitContract);
    }

    @Test
    void updateUnitPref_rejectsStoragePurpose() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateUnitPref("uid-1", "temperature", "STORAGE", "mg/m3", null, "admin"));
        verifyNoInteractions(configUnitMapper);
    }

    @Test
    void updateUnitPref_rejectsStandardPurpose() {
        // STANDARD 是 seed 维护的标准口径行（standard 模式读出口），同 STORAGE 不开放用户写（对齐 ADM 配置域只写 MONITOR/HISTORY）
        assertThrows(IllegalArgumentException.class,
                () -> service.updateUnitPref("uid-1", "temperature", "STANDARD", "mg/m3", null, "admin"));
        verifyNoInteractions(configUnitMapper);
    }

    @Test
    void updateUnitPref_rejectsUnknownPurpose() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateUnitPref("uid-1", "temperature", "DISPLAY", "mg/m3", null, "admin"));
    }

    @Test
    void updateUnitPref_upsertsThenInvalidatesUidCache() {
        AsmConfigUnit saved = service.updateUnitPref("uid-1", "temperature", "HISTORY", "ug/m3", null, "alice");

        ArgumentCaptor<AsmConfigUnit> captor = ArgumentCaptor.forClass(AsmConfigUnit.class);
        verify(configUnitMapper).upsert(captor.capture());
        assertEquals("HISTORY", captor.getValue().getPurpose());
        assertEquals("ug/m3", captor.getValue().getUnit());
        assertEquals("alice", captor.getValue().getUpdatedBy());
        assertEquals(saved, captor.getValue());
        verify(unitContract).invalidate("uid-1", "temperature");
    }

    @Test
    void listUnitPrefs_readsMonitorAndHistoryPurposes() {
        when(configUnitMapper.selectByPurpose("MONITOR")).thenReturn(java.util.Collections.emptyList());
        when(configUnitMapper.selectByPurpose("HISTORY")).thenReturn(java.util.Collections.emptyList());

        service.listUnitPrefs();

        verify(configUnitMapper).selectByPurpose("MONITOR");
        verify(configUnitMapper).selectByPurpose("HISTORY");
        verify(configUnitMapper, never()).selectByPurpose("STORAGE");
        verifyNoInteractions(unitContract);
    }

    // ===== PUT config-unit displayPrecision 校验（单位设置抽屉）=====

    @Test
    void updateUnitPref_precisionBounds0And6Accepted() {
        service.updateUnitPref("uid-1", "temperature", "MONITOR", "mg/m3", 0, "admin");
        service.updateUnitPref("uid-1", "temperature", "MONITOR", "mg/m3", 6, "admin");
        verify(configUnitMapper, times(2)).upsert(ArgumentCaptor.forClass(AsmConfigUnit.class).capture());
    }

    @Test
    void updateUnitPref_precisionOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateUnitPref("uid-1", "temperature", "MONITOR", "mg/m3", -1, "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateUnitPref("uid-1", "temperature", "MONITOR", "mg/m3", 7, "admin"));
        verifyNoInteractions(configUnitMapper);
    }

    @Test
    void updateUnitPref_precisionNullAllowedAndPassedThrough() {
        AsmConfigUnit saved = service.updateUnitPref("uid-1", "temperature", "MONITOR", "mg/m3", null, "admin");
        assertEquals(null, saved.getDisplayPrecision());
    }
}
