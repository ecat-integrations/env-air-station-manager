package com.ecat.integration.EnvAirStationManagerIntegration.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;

/**
 * 三态判定纯函数测试（移植 ADM AirDeviceBindingServiceStateTest 的判定矩阵）：
 * CONFIGURED（logic+phy 都在）/ UNBOUND（其一在）/ NOT_CREATED（均不在）。
 */
class StationDeviceBindingServiceStateTest {

    private final DeviceBase device = mock(DeviceBase.class);
    private final ConfigEntry entry = mock(ConfigEntry.class);

    @Test
    void configuredWhenLogicAndPhyBothActive() {
        assertEquals(DeviceState.CONFIGURED,
                StationDeviceBindingService.determineState(device, entry, device));
    }

    @Test
    void unboundWhenLogicWithoutPhy() {
        assertEquals(DeviceState.UNBOUND,
                StationDeviceBindingService.determineState(device, entry, null));
    }

    @Test
    void unboundWhenOnlyDisabledEntryRemains() {
        assertEquals(DeviceState.UNBOUND,
                StationDeviceBindingService.determineState(null, entry, null));
    }

    @Test
    void notCreatedWhenNothingExists() {
        assertEquals(DeviceState.NOT_CREATED,
                StationDeviceBindingService.determineState(null, null, null));
    }

    @Test
    void unboundWhenPhyMissingThoughEntryPresent() {
        DeviceBase logic = mock(DeviceBase.class);
        when(logic.getEntry()).thenReturn(null);
        assertEquals(DeviceState.UNBOUND,
                StationDeviceBindingService.determineState(logic, null, null));
    }
}
