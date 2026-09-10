package com.ecat.integration.EnvAirStationManagerIntegration.api;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AirStationSdkImpl;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSnapshotService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDK 控制出口：origin=LOCAL、caller 透传消费方集成坐标、DTO 映射（记录 id/result）。
 * 与 REST 路同收口 AsmControlService（origin 双路同源验证）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AirStationSdkControlTest {

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private AsmSnapshotService snapshotService;
    @Mock
    private AsmAlarmRecordMapper alarmRecordMapper;
    @Mock
    private AsmControlService controlService;
    @Mock
    private com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService labelService;

    private AirStationSdk sdk;

    @BeforeEach
    void setUp() {
        sdk = new AirStationSdkImpl(historyMapper, unitContract, snapshotService,
                alarmRecordMapper, labelService, controlService);
    }

    @Test
    void control_routesThroughControlServiceWithLocalOriginAndCaller() {
        AsmControlRecord rec = AsmControlRecord.builder()
                .id(11L).origin(AsmControlOrigin.LOCAL).caller("com.ecat:integration-x")
                .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                .action("WRITE").requestedValue("30.0")
                .result(AsmControlResult.SUCCESS).durationMs(120L).build();
        when(controlService.execute(AsmControlOrigin.LOCAL, "com.ecat:integration-x",
                "logicdevice_station.th", "temperature", "30.0")).thenReturn(rec);

        SdkControlResult out = sdk.control("logicdevice_station.th", "temperature", "30.0",
                "com.ecat:integration-x");

        verify(controlService).execute(AsmControlOrigin.LOCAL, "com.ecat:integration-x",
                "logicdevice_station.th", "temperature", "30.0");
        assertEquals(Long.valueOf(11L), out.getRecordId());
        assertEquals("LOCAL", out.getOrigin());
        assertEquals("SUCCESS", out.getResult());
        assertEquals(Long.valueOf(120L), out.getDurationMs());
    }
}
