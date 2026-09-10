package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmStatParamRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * stat-params 端点与 SDK 同源：复用 {@link AirStationSdk#listStatParams()} 同一构建结果逐行包装
 * （meta 平铺透传，不另造投影），并追加历史页中文契约字段 {@code display_unit}（storageUnit →
 * UnitInfo.getDisplayName 显示串）与 {@code device_label}（槽中文名，AsmDeviceLabelService 同源）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmStatParamsControllerTest {

    @Mock
    private AirStationSdk sdk;

    private AsmStatParamsController controller;

    @BeforeEach
    void setUp() {
        // registry 桩：stat-params 行只用 slotLabel（不查 registry），桩仅占位
        controller = new AsmStatParamsController(sdk, new AsmDeviceLabelService(uid -> null));
    }

    @Test
    void list_reusesSdkRowsAndAppendsDisplayUnitAndDeviceLabel() {
        SdkParamMeta ac1Temp = SdkParamMeta.builder()
                .logicDeviceUniqueId("logicdevice_station.air_conditioner.ac1")
                .attrId("temperature").paramDisplayName("温度")
                .storageUnit("TemperatureUnit.CELSIUS").applicableGranularityMask(7)
                .build();
        when(sdk.listStatParams()).thenReturn(Arrays.asList(ac1Temp));

        AjaxResult result = controller.list();

        verify(sdk).listStatParams();
        List<?> rows = (List<?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, rows.size());
        AsmStatParamRowDto row = (AsmStatParamRowDto) rows.get(0);
        // SDK 行同源透传（不另造投影），旧字段平铺路径不变
        assertSame(ac1Temp, row.getMeta());
        // display_unit：storageUnit 枚举全名 → core UnitInfo.getDisplayName()（°C）
        assertEquals("°C", row.getDisplayUnit());
        // device_label：多实例槽各自中文名（ac1→空调1，非 ac2→空调2）
        assertEquals("空调1", row.getDeviceLabel());
    }

    @Test
    void list_fallsBackToOriginalTextWhenUnitOrSlotUnresolvable() {
        SdkParamMeta row1 = SdkParamMeta.builder()
                .logicDeviceUniqueId("logicdevice_station.power_meter")
                .attrId("voltage").storageUnit("NotAnUnit.X").build();
        SdkParamMeta row2 = SdkParamMeta.builder()
                .logicDeviceUniqueId("logicdevice_unknown.uid")
                .attrId("humidity").storageUnit(null).build();
        when(sdk.listStatParams()).thenReturn(Arrays.asList(row1, row2));

        List<?> rows = (List<?>) controller.list().get(AjaxResult.DATA_TAG);

        AsmStatParamRowDto first = (AsmStatParamRowDto) rows.get(0);
        // 脏单位 key 回退原文可见；power_meter 槽中文名正常解析
        assertEquals("NotAnUnit.X", first.getDisplayUnit());
        assertEquals("智能电力监测仪表", first.getDeviceLabel());
        AsmStatParamRowDto second = (AsmStatParamRowDto) rows.get(1);
        // storageUnit null=无量纲 → display_unit null；非站房槽 uid 回退 uid 原文（不猜）
        assertNull(second.getDisplayUnit());
        assertEquals("logicdevice_unknown.uid", second.getDeviceLabel());
    }

    @Test
    void rowDto_serializesFlatJsonPaths_plusSnakeCaseContractFields() throws Exception {
        // 契约锁定：meta 平铺（旧字段 JSON 路径不变）+ display_unit/device_label 追加（Spring MVC 出口=Jackson）
        AsmStatParamRowDto row = AsmStatParamRowDto.of(SdkParamMeta.builder()
                        .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                        .paramDisplayName("温度").storageUnit("TemperatureUnit.CELSIUS")
                        .applicableGranularityMask(7).build(),
                "°C", "站房温湿度监测仪");
        String json = new ObjectMapper().writeValueAsString(row);
        assertTrue(json.contains("\"logicDeviceUniqueId\":\"logicdevice_station.th\"")
                        && json.contains("\"attrId\":\"temperature\"")
                        && json.contains("\"paramDisplayName\":\"温度\"")
                        && json.contains("\"storageUnit\":\"TemperatureUnit.CELSIUS\"")
                        && json.contains("\"applicableGranularityMask\":7"),
                "SDK meta 旧字段须平铺在顶层（@JsonUnwrapped），不得嵌套进 meta 键：" + json);
        assertTrue(json.contains("\"display_unit\":\"°C\"")
                        && json.contains("\"device_label\":\"站房温湿度监测仪\""),
                "中文契约字段须为 snake_case 键 display_unit/device_label：" + json);
        assertFalse(json.contains("\"meta\""), "不得出现 meta 包装键：" + json);
    }
}
