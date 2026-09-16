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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * snapshot 纯内存语义护栏（2026-09-16 定案）：快照只读 registry live 态，<b>无值参数按无数据处理</b>
 * （DEF 占位行，前端显 '-'，SDK 侧 value/valueText/updateTime 为 null），不回查 asm_data_sample——
 * raw 回放查询已整条删除（回放曾在压缩 chunk 上踩 TimescaleDB 兼容性雷，且命令类/未绑定参数
 * 恒缺值使回放每分钟必发必炸）。本测试锁两件事：缺值出 DEF 占位、live 值原样透出。
 * 真实 registry 注册设备（禁 sleep 全同步）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsmSnapshotBatchTest {

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final String UID_A = "logicdevice_station.camera.1";
    private static final String UID_B = "logicdevice_station.valve_group.co";

    @Mock
    private AsmUnitContract unitContract;

    private AsmSnapshotService service;
    private DeviceRegistry deviceRegistry;
    private BusRegistry busRegistry;

    @BeforeEach
    void setUp() {
        service = new AsmSnapshotService(unitContract, new AsmAlarmRegistry());
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
     * 注册一台站房设备：live 文本 attr（有值）+ def 占位 attr（无值——命令类/未绑定参数的常态）。
     * 不走数值路径（避免 unitContract mock 桩面），本测试只锁「缺值=DEF 占位、有值=LIVE」语义。
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
                        // def 占位：定义了但从未有值 → 快照按无数据处理出 DEF 占位行
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
        // 生产常态：attr 已注册但从未喂数（state 值 null）→ 快照出 DEF 占位行，不查库
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

    @Test
    void buildAll_valuelessAttrs_yieldDefPlaceholders_liveValuesPassThrough() {
        registerStationDevice(UID_A, "cam1");
        registerStationDevice(UID_B, "valve1");

        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        assertEquals(2, out.size());
        Map<String, AsmSnapshotDeviceDto> byUid = new HashMap<>();
        for (AsmSnapshotDeviceDto d : out) {
            byUid.put(d.getLogicDeviceUniqueId(), d);
        }
        // live 值原样透出
        for (String uid : Arrays.asList(UID_A, UID_B)) {
            AsmSnapshotAttrDto live = rowOf(byUid.get(uid), "online");
            assertEquals("LIVE", live.getSource());
            // 文本行走 displayValue（撕裂读契约：state 一次性快照的展示串）
            assertEquals("在线", live.getValueText());
            assertEquals(T0, live.getUpdateTime());
            // 无值参数=无数据：DEF 占位行，值/时刻全空（前端显 '-'），不回查历史表
            AsmSnapshotAttrDto def = rowOf(byUid.get(uid), "ai_running");
            assertEquals("DEF", def.getSource());
            assertNull(def.getValue());
            assertNull(def.getValueText());
            assertNull(def.getUpdateTime());
        }
    }

    @Test
    void buildForUid_valuelessAttr_defPlaceholder() {
        registerStationDevice(UID_A, "cam1");

        List<AsmSnapshotAttrDto> out = service.buildForUid(UID_A);
        AsmSnapshotAttrDto live = rowOf(out, "online");
        assertEquals("LIVE", live.getSource());
        assertEquals("在线", live.getValueText());
        AsmSnapshotAttrDto def = rowOf(out, "ai_running");
        assertEquals("DEF", def.getSource());
        assertNull(def.getValueText());
    }

    @Test
    void buildAll_preloadsUnitContractOnceWithAllUids() {
        registerStationDevice(UID_A, "cam1");
        registerStationDevice(UID_B, "valve1");

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

    private static AsmSnapshotAttrDto rowOf(List<AsmSnapshotAttrDto> rows, String attrId) {
        return rows.stream()
                .filter(a -> attrId.equals(a.getAttrId()))
                .findFirst().orElseThrow(() -> new AssertionError("attr 不存在: " + attrId));
    }
}
