package com.ecat.integration.EnvAirStationManagerIntegration.consumer;

import com.ecat.core.Bus.event.DeviceDataChangedEvent;
import com.ecat.core.Bus.event.EventContext;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDisplayValue;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import com.ecat.integration.EnvAirStationManagerIntegration.sse.AsmSseBroadcaster;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AsmSseConsumer 单测——事件 payload 组装（displayName/单位符号/状态中文/值换算出口）+ 站房过滤 +
 * 零连接短路。确定性同步：latch 等 broadcast 被调（禁 sleep）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsmSseConsumerTest {

    private static final int CAPACITY = 100;
    private static final String STATION_ID = "station-device-id";
    private static final String STATION_UID = "logicdevice_station.th";
    private static final String PHYSICAL_ID = "physical-device-id";

    @Mock
    private DeviceRegistry registry;
    @Mock
    private AsmSseBroadcaster broadcaster;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private LogicDevice stationDevice;
    @Mock
    private com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry alarmRegistry;

    private AsmSseConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AsmSseConsumer("asm-sse-test", CAPACITY, registry, broadcaster,
                new ObjectMapper(), unitContract, alarmRegistry);
    }

    @Test
    void consume_stationNumericEvent_broadcastsPayloadWithDisplayNameAndUnitSymbol() throws Exception {
        when(broadcaster.activeCount()).thenReturn(1);
        when(registry.getDeviceByID(STATION_ID)).thenReturn(stationDevice);
        when(stationDevice.getUniqueId()).thenReturn(STATION_UID);
        Map<String, com.ecat.core.State.AttributeBase<?>> attrs = new HashMap<>();
        when(stationDevice.getAttrs()).thenReturn(attrs);
        when(stationDevice.getAttrDefs()).thenReturn(Collections.singletonList(
                new LogicAttributeDefine("temperature", AttributeClass.TEMPERATURE,
                        TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 1, false, Double.class)));
        // MONITOR（custom 出口）换算：偏好 mV 类比下用 25.5 + celsius key；STANDARD（standard 出口）恒原生直通
        when(unitContract.resolveDisplay(eq(AsmUnitPurpose.MONITOR), eq(STATION_UID), eq("temperature"),
                anyDouble(), anyString()))
                .thenReturn(AsmDisplayValue.of(25.5, "temperature.celsius", false));
        when(unitContract.resolveDisplay(eq(AsmUnitPurpose.STANDARD), eq(STATION_UID), eq("temperature"),
                anyDouble(), anyString()))
                .thenReturn(AsmDisplayValue.of(25.5, "temperature.celsius", false));

        DeviceDataChangedEvent evt = new DeviceDataChangedEvent(STATION_ID, "temperature", null,
                numericState(25.5, TemperatureUnit.CELSIUS));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> jsonRef = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            jsonRef.set(inv.getArgument(0));
            latch.countDown();
            return null;
        }).when(broadcaster).broadcast(anyString());

        consumer.onEvent(evt);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "5s 内应广播（latch 确定性同步，禁 sleep）");
        JsonNode node = new ObjectMapper().readTree(jsonRef.get());
        assertEquals(STATION_UID, node.get("logicDeviceUniqueId").asText());
        assertEquals("temperature", node.get("attrId").asText());
        assertTrue(node.hasNonNull("displayName"), "payload 应带 displayName");
        assertEquals(25.5, node.get("displayValue").asDouble(), 1e-9);
        // 双模式双值同推（对齐 ADM D9=a）：standardValue/standardUnit=STANDARD 行出口，前端按 unit 模式选
        assertEquals(25.5, node.get("standardValue").asDouble(), 1e-9);
        assertEquals(node.get("unit").asText(), node.get("standardUnit").asText(),
                "standard 出口单位同经 unitSymbol 符号化（本例 stub 两 purpose 同 key）");
        assertTrue(node.hasNonNull("updateTime"), "payload 应带 updateTime");
        // 状态 key（AttributeStatus.name()）与 statusName 中文同推，前端按枚举 key 着色不按中文串
        assertEquals("NORMAL", node.get("status").asText());
        assertTrue(node.hasNonNull("statusName"));
        // displayName 来自 attr def（构造未设 displayName → 回退 attrId，回退路径也要有值）
        assertEquals("temperature", node.get("displayName").asText());
        // 帧 alarm 已改名 ruleAlarmActive（ADM 同构：仅规则 episode 侧，非设备级报警布尔；无活跃 episode=false）
        assertTrue(node.has("ruleAlarmActive"), "帧字段应为 ruleAlarmActive（alarm 已改名）");
        assertFalse(node.get("ruleAlarmActive").asBoolean(), "无活跃 episode 时 ruleAlarmActive=false");
    }

    @Test
    void consume_numericEvent_roundedToDefDisplayPrecisionHalfEven() throws Exception {
        when(broadcaster.activeCount()).thenReturn(1);
        when(registry.getDeviceByID(STATION_ID)).thenReturn(stationDevice);
        when(stationDevice.getUniqueId()).thenReturn(STATION_UID);
        when(stationDevice.getAttrs()).thenReturn(new HashMap<>());
        when(stationDevice.getAttrDefs()).thenReturn(Collections.singletonList(
                new LogicAttributeDefine("dust", AttributeClass.PM10,
                        TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 2, false, Double.class)));
        // 出口换算后全精度 160.67183333333332 → def displayPrecision 2、HALF_EVEN → 160.67
        when(unitContract.resolveDisplay(eq(AsmUnitPurpose.MONITOR), eq(STATION_UID), eq("dust"),
                anyDouble(), anyString()))
                .thenReturn(AsmDisplayValue.of(160.67183333333332d, "dust.key", false));
        when(unitContract.resolveDisplay(eq(AsmUnitPurpose.STANDARD), eq(STATION_UID), eq("dust"),
                anyDouble(), anyString()))
                .thenReturn(AsmDisplayValue.of(160.67d, "dust.key", false));

        AtomicReference<String> jsonRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            jsonRef.set(inv.getArgument(0));
            latch.countDown();
            return null;
        }).when(broadcaster).broadcast(anyString());

        consumer.onEvent(new DeviceDataChangedEvent(STATION_ID, "dust", null,
                numericState(160.67183333333332d, TemperatureUnit.CELSIUS)));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "5s 内应广播（latch 确定性同步，禁 sleep）");
        JsonNode node = new ObjectMapper().readTree(jsonRef.get());
        assertEquals(160.67, node.get("displayValue").asDouble(), 1e-9,
                "数值出口应按 def displayPrecision HALF_EVEN 修约（用户实测：160.67183… → 160.67）");
        assertEquals(160.67, node.get("standardValue").asDouble(), 1e-9,
                "standard 出口独立按 STANDARD 行换算（与 MONITOR 出口各算各的，双值同推互不串台）");
    }

    @Test
    void consume_numericEvent_defMissing_defaultsTo2Digits() throws Exception {
        when(broadcaster.activeCount()).thenReturn(1);
        when(registry.getDeviceByID(STATION_ID)).thenReturn(stationDevice);
        when(stationDevice.getUniqueId()).thenReturn(STATION_UID);
        when(stationDevice.getAttrs()).thenReturn(new HashMap<>());
        when(stationDevice.getAttrDefs()).thenReturn(Collections.emptyList());
        when(unitContract.resolveDisplay(eq(AsmUnitPurpose.MONITOR), eq(STATION_UID), eq("dust"),
                anyDouble(), anyString()))
                .thenReturn(AsmDisplayValue.of(1.0055d, "dust.key", false));
        when(unitContract.resolveDisplay(eq(AsmUnitPurpose.STANDARD), eq(STATION_UID), eq("dust"),
                anyDouble(), anyString()))
                .thenReturn(AsmDisplayValue.of(1.0055d, "dust.key", false));

        AtomicReference<String> jsonRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            jsonRef.set(inv.getArgument(0));
            latch.countDown();
            return null;
        }).when(broadcaster).broadcast(anyString());

        consumer.onEvent(new DeviceDataChangedEvent(STATION_ID, "dust", null,
                numericState(1.0055d, TemperatureUnit.CELSIUS)));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        JsonNode node = new ObjectMapper().readTree(jsonRef.get());
        assertEquals(1.01, node.get("displayValue").asDouble(), 1e-9, "def 缺席走默认 2 位 HALF_EVEN");
    }

    @Test
    void consume_physicalDeviceEvent_skipped() {
        when(broadcaster.activeCount()).thenReturn(1);
        DeviceBase physical = mock(DeviceBase.class);
        when(registry.getDeviceByID(PHYSICAL_ID)).thenReturn(physical);

        consumer.onEvent(new DeviceDataChangedEvent(PHYSICAL_ID, "x", null,
                numericState(1.0, TemperatureUnit.CELSIUS)));

        verify(broadcaster, never()).broadcast(anyString());
    }

    @Test
    void consume_zeroActiveConnections_shortCircuits() {
        when(broadcaster.activeCount()).thenReturn(0);

        consumer.onEvent(new DeviceDataChangedEvent(STATION_ID, "temperature", null,
                numericState(25.5, TemperatureUnit.CELSIUS)));

        verify(broadcaster, never()).broadcast(anyString());
        verify(registry, never()).getDeviceByID(anyString());
    }

    private static AttrState<?> numericState(double value, TemperatureUnit unit) {
        return AttrState.builder()
                .deviceId("d").attrId("a").value((Object) value)
                .valueType(Object.class)
                .status(AttributeStatus.NORMAL)
                .statuses(Collections.singleton(AttributeStatus.NORMAL))
                .nativeUnit(unit)
                .context(EventContext.root(EventContext.Source.DEVICE_POLL, null))
                .lastUpdated(Instant.parse("2026-08-19T03:00:00Z"))
                .build();
    }
}
