package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlSubmission;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.same;

/**
 * 本站 web 控制入口：caller 取认证 principal 透传、origin=LOCAL（本站页面操作=本站自身发起，
 * 非第三方代传）、unit 可选（缺省=不指定单位 / full string=换算 / 非法 400）、
 * 响应含记录 id+result；GET /{id} 单查（SSE 重连补偿）三态：PENDING 中查 / 终态 / 不存在明确 IAE。
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
    void execute_passesPrincipalAsCallerAndLocalOrigin() {
        AsmControlRecord rec = AsmControlRecord.builder()
                .id(9L).origin(AsmControlOrigin.LOCAL).caller("admin")
                .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                .action("WRITE").requestedValue("30.0")
                .result(AsmControlResult.PENDING).build();
        Executor notifyExecutor = Runnable::run;
        when(controlService.notifyExecutor()).thenReturn(notifyExecutor);
        when(controlService.executeAsync(AsmControlOrigin.LOCAL, "admin",
                "logicdevice_station.th", "temperature", "30.0", null, notifyExecutor))
                .thenReturn(new AsmControlSubmission(rec, CompletableFuture.completedFuture(rec)));

        AjaxResult result = controller.executeForCaller(new AsmControlController.AsmControlRequest(
                "logicdevice_station.th", "temperature", "30.0"), "admin");

        verify(controlService).executeAsync(AsmControlOrigin.LOCAL, "admin",
                "logicdevice_station.th", "temperature", "30.0", null, notifyExecutor);
        assertEquals(9L, ((AsmControlRecord) result.get(AjaxResult.DATA_TAG)).getId());
        assertEquals(AsmControlResult.PENDING,
                ((AsmControlRecord) result.get(AjaxResult.DATA_TAG)).getResult());
    }

    @Test
    void execute_unitField_parsedToFromUnit() {
        AsmControlController.AsmControlRequest request = new AsmControlController.AsmControlRequest(
                "logicdevice_station.th", "temperature", "26.5");
        request.setUnit("TemperatureUnit.CELSIUS");
        Executor notifyExecutor = Runnable::run;
        AsmControlRecord record = AsmControlRecord.builder().id(10L).build();
        when(controlService.notifyExecutor()).thenReturn(notifyExecutor);
        when(controlService.executeAsync(AsmControlOrigin.LOCAL, "admin", "logicdevice_station.th",
                "temperature", "26.5", TemperatureUnit.CELSIUS, notifyExecutor))
                .thenReturn(new AsmControlSubmission(record, CompletableFuture.completedFuture(record)));

        controller.executeForCaller(request, "admin");

        verify(controlService).executeAsync(AsmControlOrigin.LOCAL, "admin", "logicdevice_station.th",
                "temperature", "26.5", TemperatureUnit.CELSIUS, notifyExecutor);
    }

    @Test
    void execute_blankOrNullUnit_meansNoUnitConversion() {
        // 向后兼容锁：存量调用方不带 unit 字段（缺省 ""）/显式 null → 均按属性默认单位写入
        Executor notifyExecutor = Runnable::run;
        AsmControlRecord record = AsmControlRecord.builder().id(11L).build();
        when(controlService.notifyExecutor()).thenReturn(notifyExecutor);
        when(controlService.executeAsync(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AsmControlSubmission(record, CompletableFuture.completedFuture(record)));

        controller.executeForCaller(new AsmControlController.AsmControlRequest(
                "logicdevice_station.th", "temperature", "26.5"), "admin");
        AsmControlController.AsmControlRequest explicitNull =
                new AsmControlController.AsmControlRequest("logicdevice_station.th", "temperature", "26.5");
        explicitNull.setUnit(null);
        controller.executeForCaller(explicitNull, "admin");

        verify(controlService, times(2)).executeAsync(eq(AsmControlOrigin.LOCAL), eq("admin"),
                eq("logicdevice_station.th"), eq("temperature"), eq("26.5"), isNull(),
                same(notifyExecutor));
    }

    @Test
    void execute_symbolUnitRejectedBeforeFunnel() {
        assertThrows(IllegalArgumentException.class, () -> {
            AsmControlController.AsmControlRequest request = new AsmControlController.AsmControlRequest(
                    "logicdevice_station.th", "temperature", "26.5");
            request.setUnit("°C");
            controller.executeForCaller(request, "admin");
        });
        verifyNoInteractions(controlService);
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
