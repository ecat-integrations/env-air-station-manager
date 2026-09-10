package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmControlRecordMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REMOTE 控制入口：caller 取认证 principal 透传、origin=REMOTE 汇入统一控制服务、响应含记录 id+result；
 * GET /{id} 单查（SSE 重连补偿）三态：PENDING 中查 / 终态 / 不存在明确 IAE。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmControlControllerTest {

    @Mock
    private AsmControlService controlService;
    @Mock
    private AsmControlRecordMapper recordMapper;

    private AsmControlController controller;

    @BeforeEach
    void setUp() {
        controller = new AsmControlController(controlService, recordMapper);
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

    private static AsmControlRecord rec(long id, AsmControlResult result) {
        return AsmControlRecord.builder()
                .id(id).origin(AsmControlOrigin.REMOTE).caller("admin")
                .logicDeviceUniqueId("logicdevice_station.th").attrId("fan_speed")
                .action("COMMAND").requestedValue("high")
                .result(result).build();
    }

    @Test
    void getById_returnsPendingRecordMidFlight() {
        when(recordMapper.selectById(20L)).thenReturn(rec(20L, AsmControlResult.PENDING));

        AjaxResult result = controller.getById(20L);

        assertEquals(AsmControlResult.PENDING,
                ((AsmControlRecord) result.get(AjaxResult.DATA_TAG)).getResult());
    }

    @Test
    void getById_returnsTerminalRecord() {
        when(recordMapper.selectById(21L)).thenReturn(rec(21L, AsmControlResult.SUCCESS));

        AjaxResult result = controller.getById(21L);

        assertEquals(21L, ((AsmControlRecord) result.get(AjaxResult.DATA_TAG)).getId());
        assertEquals(AsmControlResult.SUCCESS,
                ((AsmControlRecord) result.get(AjaxResult.DATA_TAG)).getResult());
    }

    @Test
    void getById_unknownIdRejectedExplicitly() {
        when(recordMapper.selectById(999L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> controller.getById(999L));
    }
}
