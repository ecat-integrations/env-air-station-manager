package com.ecat.integration.EnvAirStationManagerIntegration.api;

import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AirStationSdkImpl;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSnapshotService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import com.ecat.integration.logicdevice.Meta.AttrIdStation.StandardGas;
import com.ecat.integration.logicdevice.Meta.DeviceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * queryCustomSnapshot SDK 装配护栏：请求形状由 snapshot service 统一收口，SDK 不造第二套校验口径。
 */
@ExtendWith(MockitoExtension.class)
class AirStationSdkCustomSnapshotTest {

    @Mock private AsmHistoryQueryMapper historyMapper;
    @Mock private AsmUnitContract unitContract;
    @Mock private AsmSnapshotService snapshotService;
    @Mock private AsmAlarmRecordMapper alarmRecordMapper;
    @Mock private AsmControlService controlService;

    @Test
    void queryCustomSnapshotDelegatesToSnapshotService() {
        AirStationSdk sdk = new AirStationSdkImpl(historyMapper, unitContract, snapshotService,
                alarmRecordMapper, null, controlService);
        SdkCustomSnapshotRequest request = SdkCustomSnapshotRequest.builder()
                .uid(DeviceId.Station.STANDARD_GAS_SO2)
                .projections(Collections.singletonList(SdkCustomSnapshotProjection.builder()
                        .attrId(StandardGas.GAS_PRESSURE_REMAINING)
                        .targetUnitFullName("PressureUnit.MPA")
                        .displayPrecision(3)
                        .build()))
                .build();
        List<SdkCustomSnapshotAttr> expected = Collections.emptyList();
        org.mockito.Mockito.when(snapshotService.queryCustomSnapshot(request)).thenReturn(expected);

        assertSame(expected, sdk.queryCustomSnapshot(request));
        verify(snapshotService).queryCustomSnapshot(request);
        verifyNoInteractions(unitContract);
    }
}
