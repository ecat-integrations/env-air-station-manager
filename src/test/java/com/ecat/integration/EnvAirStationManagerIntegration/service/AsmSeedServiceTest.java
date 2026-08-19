package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.NumberAttribute;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigUnitMapper;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AsmSeedService 单测——启动枚举过滤（airstation 前缀 / numeric attr）/ insertIfAbsent 默认值 /
 * 进程内 seen 幂等 / 直写后缓存失效。
 *
 * <p>LogicDevice 用 Mockito class-mock（Objenesis 绕过构造，只 stub getUniqueId/getAttrs 两个读取面）。
 * 无 sleep 全同步。</p>
 */
@ExtendWith(MockitoExtension.class)
class AsmSeedServiceTest {

    /** 可实例化的最小 NumberAttribute（NumberAttribute 是抽象类）。 */
    static class TestNumAttr extends NumberAttribute<Double> {
        TestNumAttr(String attrId, UnitInfo nativeUnit) {
            super(attrId, AttributeClass.TEMPERATURE, nativeUnit, nativeUnit, 1, true, true);
        }

        @Override
        protected Double convertToType(double value) {
            return value;  // seed 只读元数据（id/nativeUnit），换算路径不被触达
        }
    }

    @Mock
    private AsmConfigStatMapper configStatMapper;
    @Mock
    private AsmConfigUnitMapper configUnitMapper;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private LogicDevice stationDevice;
    @Mock
    private LogicDevice analyzerDevice;
    @Mock
    private AttributeBase<?> textAttr;

    private AsmSeedService service;

    @BeforeEach
    void setUp() {
        service = new AsmSeedService(configStatMapper, configUnitMapper, unitContract);
    }

    private static Map<String, AttributeBase<?>> attrs(AttributeBase<?>... list) {
        Map<String, AttributeBase<?>> m = new LinkedHashMap<>();
        for (AttributeBase<?> a : list) {
            m.put(a.getAttributeID(), a);
        }
        return m;
    }

    @Test
    void seedStartup_numericStationAttrs_getDefaultRows() {
        TestNumAttr temperature = new TestNumAttr("temperature", TemperatureUnit.CELSIUS);
        when(stationDevice.getUniqueId()).thenReturn("logicdevice_station.th");
        when(stationDevice.getAttrs()).thenReturn(attrs(temperature));

        int count = service.seedStartup(Arrays.asList(stationDevice));

        assertEquals(1, count);
        ArgumentCaptor<AsmConfigStat> statCap = ArgumentCaptor.forClass(AsmConfigStat.class);
        verify(configStatMapper).insertIfAbsent(statCap.capture());
        assertEquals("logicdevice_station.th", statCap.getValue().getLogicDeviceUniqueId());
        assertEquals("temperature", statCap.getValue().getAttrId());
        assertEquals(Boolean.TRUE, statCap.getValue().getEnabled(), "默认开");
        assertEquals(Integer.valueOf(7), statCap.getValue().getGranularityMask(), "默认三粒度全开");
        assertEquals("BOTH", statCap.getValue().getMaterializationMode());

        ArgumentCaptor<AsmConfigUnit> unitCap = ArgumentCaptor.forClass(AsmConfigUnit.class);
        verify(configUnitMapper).insertIfAbsent(unitCap.capture());
        assertEquals("STORAGE", unitCap.getValue().getPurpose());
        assertEquals("TemperatureUnit.CELSIUS", unitCap.getValue().getUnit(), "native unit getFullUnitString key 形式");
        assertEquals("ASM", unitCap.getValue().getCreatedBy());

        verify(unitContract).invalidate("logicdevice_station.th", "temperature");
    }

    @Test
    void seedStartup_nullNativeUnit_seedsEmptyStringPlaceholder() {
        TestNumAttr dimensionless = new TestNumAttr("uv_index", null);
        when(stationDevice.getUniqueId()).thenReturn("logicdevice_station.cleanliness");
        when(stationDevice.getAttrs()).thenReturn(attrs(dimensionless));

        service.seedStartup(Arrays.asList(stationDevice));

        ArgumentCaptor<AsmConfigUnit> cap = ArgumentCaptor.forClass(AsmConfigUnit.class);
        verify(configUnitMapper).insertIfAbsent(cap.capture());
        assertEquals("", cap.getValue().getUnit(), "无单位空串占位（行存在=已 seed；读出口显原生）");
    }

    @Test
    void seedStartup_ignoresNonStationDevicesAndNonNumericAttrs() {
        when(analyzerDevice.getUniqueId()).thenReturn("logicdevice.so2");  // ADM 域分析仪
        when(stationDevice.getUniqueId()).thenReturn("logicdevice_station.th");
        // 文本属性 mock：map key 手工给（getAttributeID 可能 final 不可 stub，且 seed 路径只按 map 迭代不读 id）
        Map<String, AttributeBase<?>> textOnly = new LinkedHashMap<>();
        textOnly.put("status_text", textAttr);
        when(stationDevice.getAttrs()).thenReturn(textOnly);

        int count = service.seedStartup(Arrays.asList(analyzerDevice, stationDevice));

        assertEquals(0, count, "非站房设备跳过 + 非 numeric attr 跳过");
        verifyNoInteractions(configStatMapper, configUnitMapper, unitContract);
    }

    @Test
    void seedSeries_seenSet_makesIdempotentWithinProcess() {
        assertTrue(service.seedSeries("logicdevice_station.th", "temperature", "TemperatureUnit.CELSIUS"));
        // 第二次同 series：seen 去重，零 DB
        assertFalse(service.seedSeries("logicdevice_station.th", "temperature", "TemperatureUnit.CELSIUS"));
        verify(configStatMapper, times(1)).insertIfAbsent(any(AsmConfigStat.class));
        verify(configUnitMapper, times(1)).insertIfAbsent(any(AsmConfigUnit.class));
        verify(unitContract, times(1)).invalidate(anyString(), anyString());
    }

    @Test
    void seedSeries_strictNullArgs_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> service.seedSeries(null, "a", "u"));
        assertThrows(IllegalArgumentException.class,
                () -> service.seedSeries("u", null, "u"));
        assertThrows(IllegalArgumentException.class,
                () -> service.seedSeries("u", "a", null));
        verifyNoInteractions(configStatMapper);
    }

    @Test
    void seedStartup_nullDevices_throw() {
        assertThrows(IllegalArgumentException.class, () -> service.seedStartup(null));
        verify(configStatMapper, never()).insertIfAbsent(any());
    }
}
