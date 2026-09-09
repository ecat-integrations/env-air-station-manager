package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk;
import com.ecat.integration.EnvAirStationManagerIntegration.api.AsmParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SDK 严格入参校验测试（任一不满足抛 IllegalArgumentException，不静默兜底）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AirStationSdkValidationTest {

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private AsmSnapshotService snapshotService;

    private AirStationSdk sdk;

    private static final Instant START = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-18T01:00:00Z");
    private static final AsmParamKey KEY =
            AsmParamKey.builder().logicDeviceUniqueId("logicdevice_station.d").attrId("voltage").build();

    @BeforeEach
    void setUp() {
        sdk = new AirStationSdkImpl(historyMapper, unitContract, snapshotService, org.mockito.Mockito.mock(com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper.class), org.mockito.Mockito.mock(com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService.class), org.mockito.Mockito.mock(com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService.class));
    }

    @Test
    void nullOrEmptyParamsMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> sdk.queryStat(null, AsmStatGranularity.HOUR, AsmIntervalMode.BACK, START, END));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryStat(
                Collections.emptyList(), AsmStatGranularity.HOUR, AsmIntervalMode.BACK, START, END));
    }

    @Test
    void nullEnumArgsMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> sdk.queryStat(Arrays.asList(KEY), null, AsmIntervalMode.BACK, START, END));
        assertThrows(IllegalArgumentException.class,
                () -> sdk.queryStat(Arrays.asList(KEY), AsmStatGranularity.HOUR, null, START, END));
    }

    @Test
    void nullOrReversedWindowMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> sdk.queryStat(Arrays.asList(KEY), AsmStatGranularity.HOUR, AsmIntervalMode.BACK, null, END));
        assertThrows(IllegalArgumentException.class,
                () -> sdk.queryStat(Arrays.asList(KEY), AsmStatGranularity.HOUR, AsmIntervalMode.BACK, START, null));
        assertThrows(IllegalArgumentException.class,
                () -> sdk.queryStat(Arrays.asList(KEY), AsmStatGranularity.HOUR, AsmIntervalMode.BACK, END, START));
    }

    @Test
    void minuteWindowOver31DaysMustThrow() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryStat(
                Arrays.asList(KEY), AsmStatGranularity.MINUTE, AsmIntervalMode.BACK,
                START, START.plusSeconds(32L * 24 * 3600)));
    }

    @Test
    void fiveMinWindowOver31DaysMustThrow() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryStat(
                Arrays.asList(KEY), AsmStatGranularity.FIVE_MIN, AsmIntervalMode.BACK,
                START, START.plusSeconds(32L * 24 * 3600)));
    }

    @Test
    void hourWindowOver400DaysMustThrow() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryStat(
                Arrays.asList(KEY), AsmStatGranularity.HOUR, AsmIntervalMode.BACK,
                START, START.plusSeconds(401L * 24 * 3600)));
    }

    @Test
    void snapshotNullOrBlankUidMustThrow() {
        assertThrows(IllegalArgumentException.class, () -> sdk.querySnapshot(null));
        assertThrows(IllegalArgumentException.class, () -> sdk.querySnapshot("  "));
    }
}
