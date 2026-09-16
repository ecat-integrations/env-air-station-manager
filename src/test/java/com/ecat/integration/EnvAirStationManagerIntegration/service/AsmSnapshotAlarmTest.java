package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.DeviceLifecycleEvent;
import com.ecat.core.Bus.event.EventContext;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttrState;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmAlarmActiveDto;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * snapshot 设备级 alarm 字段（P2）TDD：两源并集（registry 活跃 episode ∪ ALARM_STATUS 类 attr 自报）
 * + 选项 key 判定（非 i18n 文案）。真实 registry+bus 事件链注册站房设备（同 AirStationSdkAssemblyTest
 * 模式），禁 sleep 全同步。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsmSnapshotAlarmTest {

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final String UID = "logicdevice_station.th";

    @Mock
    private AsmUnitContract unitContract;

    private AsmAlarmRegistry alarmRegistry;
    private AsmSnapshotService service;

    @BeforeEach
    void setUp() {
        alarmRegistry = new AsmAlarmRegistry();
        service = new AsmSnapshotService(unitContract, alarmRegistry);
    }

    @AfterEach
    void tearDown() {
        LogicDeviceManager.getInstance().reset();
    }

    /** 注册带一个 ALARM_STATUS 类 attr（temp_alarm，选项 key 值）的站房逻辑设备。 */
    private void registerStationDevice(String alarmOptionKey) {
        DeviceRegistry registry = new DeviceRegistry();
        BusRegistry busRegistry = new BusRegistry();
        LogicDeviceManager manager = LogicDeviceManager.getInstance();
        manager.reset();
        manager.setRegistry(registry);
        manager.setBusRegistry(busRegistry);

        ConfigEntry entry = new ConfigEntry();
        entry.setEntryId(UID);
        entry.setUniqueId(UID);
        Map<String, Object> data = new HashMap<>();
        data.put("name", "th-station");
        entry.setData(data);
        LogicDevice device = new LogicDevice(entry) {
            @Override public List<LogicAttributeDefine> getAttrDefs() {
                return Collections.singletonList(new LogicAttributeDefine(
                        "temp_alarm", AttributeClass.ALARM_STATUS, null, null, 0, false, String.class));
            }
            @Override protected String getMappingType() { return "TEST-ASM"; }
            @Override public void start() {}
            @Override public void stop() {}
            @Override public void release() {}
            @Override public Map<String, ILogicAttribute<?>> getAttrMap() { return new HashMap<>(); }
        };
        device.load(EcatCore.getInstance());
        device.init();
        AttributeBase<?> alarmAttr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(alarmAttr.getAttributeID()).thenReturn("temp_alarm");
        lenient().doReturn(AttrState.builder()
                .deviceId(device.getId()).attrId("temp_alarm")
                .valueType(Object.class)
                .status(com.ecat.core.State.AttributeStatus.NORMAL)
                .context(EventContext.root(EventContext.Source.DEVICE_POLL, "test"))
                .value(alarmOptionKey)
                .displayValue("alarm" == alarmOptionKey ? "报警" : "正常")
                .lastUpdated(T0)
                .build()).when(alarmAttr).getState();
        device.setAttribute(alarmAttr);
        registry.register(device.getId(), device);
        busRegistry.publish(BusEvent.of(
                com.ecat.core.Bus.BusTopic.DEVICE_LIFECYCLE.getTopicName(),
                new DeviceLifecycleEvent(device.getId(), "com.ecat:integration-env-air-station-manager",
                        device.getId(), device.getId(), DeviceLifecycleEvent.Action.CREATE),
                EventContext.root(EventContext.Source.SYSTEM, null)));
    }

    @Test
    void noAlarmSource_alarmFalseWithEmptyList() {
        registerStationDevice("normal");
        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        assertEquals(1, out.size());
        // 设备级 alarm boolean 已删（ADM 同构）：无报警=activeAlarms 空（卡片徽章由前端对全 attr statuses 求并集）
        assertTrue(out.get(0).getActiveAlarms().isEmpty());
    }

    @Test
    void liveRow_carriesStatusEnumName_notOnlyChineseDescription() {
        registerStationDevice("normal");
        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        List<com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto> attrs =
                out.get(0).getAttrs();
        assertEquals(1, attrs.size());
        // 状态 key（AttributeStatus.name()）供前端枚举着色，避免按中文 description 串配色
        assertEquals("NORMAL", attrs.get(0).getStatus());
        assertEquals("数据有效", attrs.get(0).getStatusName());
    }

    @Test
    void attrSideAlarm_optionKeyNotNormal_alarmTrueWithAttrEntry() {
        registerStationDevice("alarm");  // 选项 key 判定，非「正常」文案比对
        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        assertEquals(1, out.get(0).getActiveAlarms().size());
        AsmAlarmActiveDto dto = out.get(0).getActiveAlarms().get(0);
        assertEquals("temp_alarm", dto.getAttrId());
        assertEquals(AsmAlarmActiveDto.TYPE_ATTR_STATUS, dto.getAlarmType());
        assertEquals("报警", dto.getRuleName());   // displayValue 只作展示
    }

    @Test
    void registrySideAlarm_unionBothSources() {
        registerStationDevice("alarm");
        alarmRegistry.put(new AsmAlarmRegistry.ActiveAlarm(
                UID, "temperature", "room_temp_abnormal", "温度异常", T0, T0));
        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        assertEquals(2, out.get(0).getActiveAlarms().size());   // 两源并集（attrId+类型粗粒度不重）
    }

    @Test
    void registryOnlyAlarm_attrNormal_alarmTrue() {
        registerStationDevice("normal");
        alarmRegistry.put(new AsmAlarmRegistry.ActiveAlarm(
                UID, "temperature", "room_temp_abnormal", "温度异常", T0, T0));
        List<AsmSnapshotDeviceDto> out = service.buildAll(null);
        assertEquals(1, out.get(0).getActiveAlarms().size());
        assertEquals("room_temp_abnormal", out.get(0).getActiveAlarms().get(0).getAlarmType());
    }
}
