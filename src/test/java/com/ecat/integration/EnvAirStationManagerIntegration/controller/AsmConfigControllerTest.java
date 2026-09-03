package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmConfigStatRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmConfigService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 配置端点：GET 读 / PUT 写透传 service 收口（校验+invalidate 在 service 层），operator=认证 principal。
 * config-stat 行追加 device_label/attr_label（mock registry 解析，口径与 alarm-record 行同源）。
 */
@ExtendWith(MockitoExtension.class)
class AsmConfigControllerTest {

    @Mock
    private AsmConfigService configService;

    private AsmConfigController controller;

    @BeforeEach
    void setUp() {
        // 空 registry 桩：label 回退原文（解析用例在 getStat_mapsRowsWithLabels 注入 mock registry）
        controller = new AsmConfigController(configService, new AsmDeviceLabelService(uid -> null));
    }

    @Test
    void getStat_delegatesToListStatConfig() {
        when(configService.listStatConfig()).thenReturn(Collections.<AsmConfigStat>emptyList());
        AjaxResult result = controller.getStat();
        assertEquals(Collections.emptyList(), result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void getStat_mapsRowsWithDeviceAndAttrLabels() {
        AsmConfigStat stat = AsmConfigStat.builder()
                .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                .enabled(Boolean.TRUE).granularityMask(7).materializationMode("BOTH").build();
        when(configService.listStatConfig()).thenReturn(Collections.singletonList(stat));

        LogicDevice th = mock(LogicDevice.class, withSettings().lenient());
        LogicAttributeDefine def = new LogicAttributeDefine();
        def.setAttrId("temperature");
        def.setDisplayName("温度");
        when(th.getAttrDefs()).thenReturn(Collections.singletonList(def));
        AsmConfigController controllerWithRegistry = new AsmConfigController(configService,
                new AsmDeviceLabelService(
                        uid -> "logicdevice_station.th".equals(uid) ? th : null));

        AjaxResult result = controllerWithRegistry.getStat();

        List<?> rows = (List<?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, rows.size());
        AsmConfigStatRowDto row = (AsmConfigStatRowDto) rows.get(0);
        assertSame(stat, row.getStat());
        assertEquals("站房温湿度监测仪", row.getDeviceLabel());
        assertEquals("温度", row.getAttrLabel());
    }

    @Test
    void putStat_passesCallerAsOperatorAndNotesNoBackfill() {
        AsmConfigStat saved = AsmConfigStat.builder().logicDeviceUniqueId("uid-1").attrId("temperature").build();
        when(configService.updateStatConfig("uid-1", "temperature", true, 7, "BOTH", "admin")).thenReturn(saved);

        AjaxResult result = controller.putStatForCaller(
                new AsmConfigController.ConfigStatRequest("uid-1", "temperature", true, 7, "BOTH"), "admin");

        verify(configService).updateStatConfig("uid-1", "temperature", true, 7, "BOTH", "admin");
        assertEquals(saved, result.get(AjaxResult.DATA_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("不回溯"), "PUT 响应须注明配置不回溯历史");
    }

    @Test
    void putUnit_passesCallerAndReturnsRow() {
        AsmConfigUnit saved = AsmConfigUnit.builder().logicDeviceUniqueId("uid-1").attrId("temperature")
                .purpose("HISTORY").unit("ug/m3").build();
        when(configService.updateUnitPref("uid-1", "temperature", "HISTORY", "ug/m3", null, "admin")).thenReturn(saved);

        AjaxResult result = controller.putUnitForCaller(
                new AsmConfigController.ConfigUnitRequest("uid-1", "temperature", "HISTORY", "ug/m3"), "admin");

        verify(configService).updateUnitPref("uid-1", "temperature", "HISTORY", "ug/m3", null, "admin");
        assertEquals(saved, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void putStat_nullBodyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.putStatForCaller(null, "admin"));
    }

    @Test
    void putUnit_passesDisplayPrecisionThrough() {
        AsmConfigUnit saved = AsmConfigUnit.builder().logicDeviceUniqueId("uid-1").attrId("co")
                .purpose("MONITOR").unit("AirMassUnit.MGM3").displayPrecision(3).build();
        when(configService.updateUnitPref("uid-1", "co", "MONITOR", "AirMassUnit.MGM3", 3, "admin"))
                .thenReturn(saved);

        AsmConfigController.ConfigUnitRequest req =
                new AsmConfigController.ConfigUnitRequest("uid-1", "co", "MONITOR", "AirMassUnit.MGM3");
        req.setDisplayPrecision(3);

        AjaxResult result = controller.putUnitForCaller(req, "admin");
        verify(configService).updateUnitPref("uid-1", "co", "MONITOR", "AirMassUnit.MGM3", 3, "admin");
        assertEquals(saved, result.get(AjaxResult.DATA_TAG));
    }
}
