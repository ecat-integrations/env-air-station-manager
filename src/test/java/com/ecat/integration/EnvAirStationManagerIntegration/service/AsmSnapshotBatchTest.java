package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.Bus.event.DeviceLifecycleEvent;
import com.ecat.core.Bus.event.EventContext;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttrState;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotDeviceDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import com.ecat.integration.logicdevice.LogicState.ILogicAttribute;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.ecat.core.ConfigEntry.ConfigEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * snapshot 批量化 TDD（raw 预取 N 合 1 + 单位契约批量预载）：buildAll 对多缺值设备必须
 * <b>恰好一次</b> raw 批量查询（IN 全部缺值 uid）+ 恰好一次 preload，替代逐设备串行往返
 * （远程库实测每 uid 30~140ms，37 设备串行 = 秒级页面首载税）。真实 registry 注册设备
 * （同 AsmSnapshotAlarmTest 模式），禁 sleep 全同步。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsmSnapshotBatchTest {

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final String UID_A = "logicdevice_station.camera.1";
    private static final String UID_B = "logicdevice_station.valve_group.co";

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;

    private AsmSnapshotService service;
    private DeviceRegistry deviceRegistry;
    private BusRegistry busRegistry;

    @BeforeEach
    void setUp() {
        service = new AsmSnapshotService(historyMapper, unitContract, new AsmAlarmRegistry());
        // registry 装配一次；多设备注册只 add 不 reset（reset 会抹掉已注册设备）
        deviceRegistry = new DeviceRegistry();
        busRegistry = new BusRegistry();
        LogicDeviceManager manager = LogicDeviceManager.getInstance();
        manager.reset();
        manager.setRegistry(deviceRegistry);
        manager.setBusRegistry(busRegistry);
    }

    @AfterEach
    void tearDown() {
        LogicDeviceManager.getInstance().reset();
    }

    /**
     * 注册一台站房设备：live 文本 attr（有值）+ def 占位 attr（无值→anyMissing 恒真）。
     * 不走数值路径（避免 unitContract mock 桩面），本测试只锁批量查询次数与分组正确性。
     */
    private void registerStationDevice(String uid, String name) {
        DeviceRegistry registry = deviceRegistry;
        BusRegistry busRegistry = this.busRegistry;

        ConfigEntry entry = new ConfigEntry();
        entry.setEntryId(uid);
        entry.setUniqueId(uid);
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        entry.setData(data);
        LogicDevice device = new LogicDevice(entry) {
            @Override public List<LogicAttributeDefine> getAttrDefs() {
                return Arrays.asList(
                        new LogicAttributeDefine("online", AttributeClass.TEXT, null, null, 0, false, String.class),
                        // def 占位：定义了但从未有值 → buildAll 必须为该设备预取 raw
                        new LogicAttributeDefine("ai_running", AttributeClass.TEXT, null, null, 0, false, String.class));
            }
            @Override protected String getMappingType() { return "TEST-ASM"; }
            @Override public void start() {}
            @Override public void stop() {}
            @Override public void release() {}
            @Override public Map<String, ILogicAttribute<?>> getAttrMap() { return new HashMap<>(); }
        };
        device.load(EcatCore.getInstance());
        device.init();
        AttributeBase<?> onlineAttr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(onlineAttr.getAttributeID()).thenReturn("online");
        lenient().doReturn(AttrState.builder()
                .deviceId(device.getId()).attrId("online")
                .valueType(Object.class)
                .status(com.ecat.core.State.AttributeStatus.NORMAL)
                .context(EventContext.root(EventContext.Source.DEVICE_POLL, "test"))
                .value("on")
                .displayValue("在线")
                .lastUpdated(T0)
                .build()).when(onlineAttr).getState();
        device.setAttribute(onlineAttr);
        // 生产形态：attr 已注册但从未喂数（state 值 null）→ buildAll 必须为该设备预取 raw 回放
        AttributeBase<?> pendingAttr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(pendingAttr.getAttributeID()).thenReturn("ai_running");
        lenient().doReturn(AttrState.builder()
                .deviceId(device.getId()).attrId("ai_running")
                .valueType(Object.class)
                .status(com.ecat.core.State.AttributeStatus.NORMAL)
                .context(EventContext.root(EventContext.Source.DEVICE_POLL, "test"))
                .value(null)
                .lastUpdated(T0)
                .build()).when(pendingAttr).getState();
        device.setAttribute(pendingAttr);
        registry.register(device.getId(), device);
        busRegistry.publish(BusEvent.of(
                com.ecat.core.Bus.BusTopic.DEVICE_LIFECYCLE.getTopicName(),
                new DeviceLifecycleEvent(device.getId(), "com.ecat:integration-env-air-station-manager",
                        device.getId(), device.getId(), DeviceLifecycleEvent.Action.CREATE),
                EventContext.root(EventContext.Source.SYSTEM, null)));
    }

    private static AsmDataSample sample(String uid, String attrId, String text) {
        return AsmDataSample.builder()
                .logicDeviceUniqueId(uid).attrId(attrId)
                .dataTime(T0).valueText(text).source("RAW")
                .build();
    }

    @Test
    void buildAll_missingDevices_queriedRawExactlyOnceWithAllUids() {
        registerStationDevice(UID_A, "cam1");
        registerStationDevice(UID_B, "valve1");
        when(historyMapper.selectLatestSamples(anyList())).thenReturn(Collections.emptyList());

        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        assertEquals(2, out.size());
        // 恰好一次批量查询，且 IN 集合含全部缺值 uid（旧实现逐设备 singletonList → 必红）
        verify(historyMapper, times(1)).selectLatestSamples(anyList());
        verify(historyMapper, never()).selectLatestSamples(Collections.singletonList(UID_A));
        verify(historyMapper, times(1)).selectLatestSamples(org.mockito.ArgumentMatchers.argThat(
                (List<String> c) -> c.size() == 2 && c.contains(UID_A) && c.contains(UID_B)));
    }

    @Test
    void buildAll_rawSamplesGroupedBackToOwnDevice() {
        registerStationDevice(UID_A, "cam1");
        registerStationDevice(UID_B, "valve1");
        when(historyMapper.selectLatestSamples(anyList())).thenReturn(Arrays.asList(
                sample(UID_A, "ai_running", "running-a"),
                sample(UID_B, "ai_running", "running-b")));

        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        Map<String, AsmSnapshotDeviceDto> byUid = new HashMap<>();
        for (AsmSnapshotDeviceDto d : out) {
            byUid.put(d.getLogicDeviceUniqueId(), d);
        }
        assertEquals("running-a", rowOf(byUid.get(UID_A), "ai_running").getValueText());
        assertEquals("running-b", rowOf(byUid.get(UID_B), "ai_running").getValueText());
        assertEquals("RAW", rowOf(byUid.get(UID_A), "ai_running").getSource());
    }

    @Test
    void buildAll_preloadsUnitContractOnceWithAllUids() {
        registerStationDevice(UID_A, "cam1");
        registerStationDevice(UID_B, "valve1");
        when(historyMapper.selectLatestSamples(anyList())).thenReturn(Collections.emptyList());

        service.buildAll(null);
        // 单位契约批量预载：一次 IN 查询全 uid（含负结果 uid 入缓存，杀逐 attr 直查税）
        verify(unitContract, times(1)).preload(org.mockito.ArgumentMatchers.argThat(
                (java.util.Collection<String> c) -> c.size() == 2 && c.contains(UID_A) && c.contains(UID_B)));
    }

    private static AsmSnapshotAttrDto rowOf(AsmSnapshotDeviceDto device, String attrId) {
        return device.getAttrs().stream()
                .filter(a -> attrId.equals(a.getAttrId()))
                .findFirst().orElseThrow(() -> new AssertionError("attr 不存在: " + attrId));
    }
}
