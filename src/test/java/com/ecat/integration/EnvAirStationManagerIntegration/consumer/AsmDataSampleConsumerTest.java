package com.ecat.integration.EnvAirStationManagerIntegration.consumer;

import com.ecat.core.Bus.event.DeviceDataChangedEvent;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.NumberAttribute;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmDataSampleMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSeedService;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ASM raw 落库 consumer 单测——同包直接调 flush(List)（手动批触发，无 sleep，worker 线程不参与）：
 * airstation 过滤（uid 前缀）/ 数值文本分槽 / 首见 numeric series 触发 seed（二次 flush 不重复）/ 空批不调 mapper。
 */
@ExtendWith(MockitoExtension.class)
class AsmDataSampleConsumerTest {

    /** 可实例化的最小 NumberAttribute（seed 读 nativeUnit 元数据）。 */
    static class TestNumAttr extends NumberAttribute<Double> {
        TestNumAttr(String attrId, UnitInfo nativeUnit) {
            super(attrId, AttributeClass.TEMPERATURE, nativeUnit, nativeUnit, 1, true, true);
        }

        @Override
        protected Double convertToType(double value) {
            return value;
        }
    }

    @Mock
    private DeviceRegistry registry;
    @Mock
    private AsmDataSampleMapper sampleMapper;
    @Mock
    private AsmSeedService seedService;
    @Mock
    private LogicDevice stationDevice;
    @Mock
    private LogicDevice analyzerDevice;
    @Mock
    private com.ecat.core.Device.DeviceBase physicalDevice;

    private AsmDataSampleConsumer consumer;

    /** deviceId → 设备桩映射（registry 反查 anyString → map 查；未注册 id 返 null=孤儿事件）。 */
    private final Map<String, com.ecat.core.Device.DeviceBase> devices = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        TestNumAttr temperature = new TestNumAttr("temperature", TemperatureUnit.CELSIUS);
        // lenient：station 桩在「过滤非站房」用例不被触达（该用例不 flush station-dev）
        org.mockito.Mockito.lenient().when(stationDevice.getUniqueId()).thenReturn("logicdevice_station.th");
        org.mockito.Mockito.lenient().when(stationDevice.getAttrs()).thenReturn(attrs(temperature));
        devices.put("station-dev", stationDevice);
        when(registry.getDeviceByID(anyString()))
                .thenAnswer(inv -> devices.get(inv.<String>getArgument(0)));
        consumer = new AsmDataSampleConsumer("asm-data-sample-test", 10, 100, 60_000L,
                registry, sampleMapper, seedService);
    }

    @AfterEach
    void tearDown() {
        consumer.shutdown();
    }

    private static Map<String, AttributeBase<?>> attrs(AttributeBase<?>... list) {
        Map<String, AttributeBase<?>> m = new LinkedHashMap<>();
        for (AttributeBase<?> a : list) {
            m.put(a.getAttributeID(), a);
        }
        return m;
    }

    private static DeviceDataChangedEvent event(String deviceId, String attrId, Object value, UnitInfo unit) {
        AttrState<Object> state = AttrState.<Object>builder()
                .deviceId(deviceId).attrId(attrId).value(value)
                .valueType(Object.class)
                .status(com.ecat.core.State.AttributeStatus.NORMAL)
                .context(com.ecat.core.Bus.event.EventContext.root(
                        com.ecat.core.Bus.event.EventContext.Source.DEVICE_POLL, "test"))
                .nativeUnit(unit)
                .lastUpdated(java.time.Instant.parse("2026-08-18T10:00:00Z"))
                .build();
        return new DeviceDataChangedEvent(deviceId, attrId, null, state);
    }

    @Test
    void flush_stationNumericEvent_landsRawSampleAndSeedsFirstSeen() {
        consumer.flush(Collections.singletonList(
                event("station-dev", "temperature", 25.5, TemperatureUnit.CELSIUS)));

        ArgumentCaptor<List<AsmDataSample>> cap = ArgumentCaptor.forClass((Class<List<AsmDataSample>>) (Class<?>) List.class);
        verify(sampleMapper).batchInsert(cap.capture());
        AsmDataSample s = cap.getValue().get(0);
        assertEquals("logicdevice_station.th", s.getLogicDeviceUniqueId(), "落库用稳定 uniqueId 非运行时 deviceId");
        assertEquals("temperature", s.getAttrId());
        assertEquals(new java.math.BigDecimal("25.5"), s.getValueNum(), "Number → valueNum 保精度");
        assertEquals("TemperatureUnit.CELSIUS", s.getUnit());
        assertEquals(java.time.Instant.parse("2026-08-18T10:00:00Z"), s.getDataTime());

        // 首见 numeric series 触发 seed（nativeUnit 来自 airstation attr 定义）
        verify(seedService).seedSeries("logicdevice_station.th", "temperature", "TemperatureUnit.CELSIUS");
    }

    @Test
    void flush_secondFlush_sameSeriesNoRepeatSeed() {
        DeviceDataChangedEvent evt = event("station-dev", "temperature", 25.5, TemperatureUnit.CELSIUS);
        consumer.flush(Collections.singletonList(evt));
        consumer.flush(Collections.singletonList(evt));

        verify(seedService, org.mockito.Mockito.times(1))
                .seedSeries("logicdevice_station.th", "temperature", "TemperatureUnit.CELSIUS");
        verify(sampleMapper, org.mockito.Mockito.times(2)).batchInsert(anyList());
    }

    @Test
    void flush_filtersNonAirstationDevices() {
        when(analyzerDevice.getUniqueId()).thenReturn("logicdevice.so2");
        devices.put("analyzer-dev", analyzerDevice);
        devices.put("physical-dev", physicalDevice);

        consumer.flush(Arrays.asList(
                event("analyzer-dev", "concentration", 1.0, null),   // ADM 域分析仪 logic device
                event("orphan-dev", "x", 1.0, null),                 // 孤儿事件（registry 查无 → null）
                event("physical-dev", "temp", 2.0, null)));          // 物理设备（非 LogicDevice）

        verify(sampleMapper, never()).batchInsert(anyList());
        verifyNoInteractions(seedService);
    }

    @Test
    void flush_stringValueGoesValueTextSlot() {
        when(stationDevice.getAttrs()).thenReturn(attrs(
                new TestNumAttr("temperature", TemperatureUnit.CELSIUS)));  // status_text 非 numeric attr
        DeviceDataChangedEvent evt = event("station-dev", "status_text", "RUNNING", null);

        consumer.flush(Collections.singletonList(evt));

        ArgumentCaptor<List<AsmDataSample>> cap = ArgumentCaptor.forClass((Class<List<AsmDataSample>>) (Class<?>) List.class);
        verify(sampleMapper).batchInsert(cap.capture());
        AsmDataSample s = cap.getValue().get(0);
        assertEquals("RUNNING", s.getValueText());
        assertEquals(null, s.getValueNum());
        // 非 numeric attr 不 seed（avg-only 引擎只物化 numeric，与 AsmSeedService.seedStartup 同口径）
        verify(seedService, never()).seedSeries(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
