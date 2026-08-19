package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REMOTE 控制入口：caller 取认证 principal 透传、origin=REMOTE 汇入统一控制服务、响应含记录 id+result。
 */
@ExtendWith(MockitoExtension.class)
class AsmControlControllerTest {

    @Mock
    private AsmControlService controlService;

    private AsmControlController controller;

    @BeforeEach
    void setUp() {
        controller = new AsmControlController(controlService);
    }

    @Test
    void execute_passesPrincipalAsCallerAndRemoteOrigin() {
        AsmControlRecord rec = AsmControlRecord.builder()
                .id(9L).origin(AsmControlOrigin.REMOTE).caller("admin")
                .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                .action("WRITE").requestedValue("30.0")
                .result(AsmControlResult.PENDING).build();
        when(controlService.execute(AsmControlOrigin.REMOTE, "admin",
                "logicdevice_station.th", "temperature", "30.0")).thenReturn(rec);

        AjaxResult result = controller.executeForCaller(new AsmControlController.AsmControlRequest(
                "logicdevice_station.th", "temperature", "30.0"), "admin");

        verify(controlService).execute(AsmControlOrigin.REMOTE, "admin",
                "logicdevice_station.th", "temperature", "30.0");
        assertEquals(9L, ((AsmControlRecord) result.get(AjaxResult.DATA_TAG)).getId());
        assertEquals(AsmControlResult.PENDING,
                ((AsmControlRecord) result.get(AjaxResult.DATA_TAG)).getResult());
    }
}
