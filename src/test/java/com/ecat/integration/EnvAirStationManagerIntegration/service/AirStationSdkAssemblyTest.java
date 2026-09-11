package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.BusTopic;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.Bus.event.DeviceLifecycleEvent;
import com.ecat.core.Bus.event.EventContext;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.State.AttributeBase;
import com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk;
import com.ecat.integration.EnvAirStationManagerIntegration.api.AsmParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkAlarmEntry;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkControlRequest;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkControlResult;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkSnapshotAttr;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkStatRow;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmStatParamMetaRow;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import com.ecat.integration.logicdevice.LogicState.ILogicAttribute;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDK 组装路径测试（mock mapper/服务，验证 DTO 行组装与降级口径，不碰 DB/运行环境）。
 *
 * <p>覆盖 queryStat 行组装 / listStatParams displayName 降级（registry 命中与未命中）/
 * querySnapshot 委派映射 / queryAlarmEntries 行映射（label 双字段经真实 label 服务解析）/
 * hour 窗口超限 / control null 枚举降 null。</p>
 *
 * <p>listStatParams 的 displayName 需 live attrs：经真实事件链注册 LogicDevice
 * （registry + BusRegistry DEVICE_LIFECYCLE，与生产同路径），测试后 reset 单例防串扰。</p>
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AirStationSdkAssemblyTest {

    private static final String UID = "logicdevice_station.asm";
    private static final String UID2 = "logicdevice_station.unreg";
    private static final Instant START = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-18T02:00:00Z");

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private AsmSnapshotService snapshotService;
    @Mock
    private AsmAlarmRecordMapper alarmRecordMapper;
    @Mock
    private AsmControlService controlService;

    private AirStationSdk sdk;

    @BeforeEach
    void setUp() {
        // label 服务走生产构造（registry 扫描）：报警条目 label 双字段复用 REST 同一解析源；
        // 未注册设备时扫描自然落空，不影响其余 mock 路径用例
        sdk = new AirStationSdkImpl(historyMapper, unitContract, snapshotService,
                alarmRecordMapper, new AsmDeviceLabelService(), controlService);
    }

    @AfterEach
    void tearDown() {
        // 单例跨用例共享，清掉本测试注册的 active 逻辑设备
        LogicDeviceManager.getInstance().reset();
    }

    private static AsmParamKey key(String uid, String attrId) {
        return AsmParamKey.builder().logicDeviceUniqueId(uid).attrId(attrId).build();
    }

    // ========== queryStat ==========

    @Test
    void queryStat_emptyOrNullRows_returnsEmptyList() {
        when(historyMapper.selectStatRows(eq("asm_stat_hour"), eq(START), eq(END),
                anyList(), eq(AsmIntervalMode.BACK.code()), eq(0), eq(0)))
                .thenReturn(Collections.emptyList());
        assertTrue(sdk.queryStat(Arrays.asList(key(UID, "voltage")),
                AsmStatGranularity.HOUR, AsmIntervalMode.BACK, START, END).isEmpty());

        when(historyMapper.selectStatRows(any(), any(), any(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(null);
        assertTrue(sdk.queryStat(Arrays.asList(key(UID, "voltage")),
                AsmStatGranularity.HOUR, AsmIntervalMode.BACK, START, END).isEmpty());
        verify(unitContract, never()).resolveUnit(any(), any(), any());
    }

    @Test
    void queryStat_multiRows_mapsAllFieldsWithStorageUnit() {
        Instant t1 = Instant.parse("2026-08-18T00:00:00Z");
        Instant t2 = Instant.parse("2026-08-18T01:00:00Z");
        AsmHistoryBucket r1 = AsmHistoryBucket.builder()
                .logicDeviceUniqueId(UID).attrId("voltage")
                .dataTime(t1).avgValue(220.5).validCount(58L).totalCount(60L).build();
        AsmHistoryBucket r2 = AsmHistoryBucket.builder()
                .logicDeviceUniqueId(UID).attrId("current")
                .dataTime(t2).avgValue(3.25).validCount(59L).totalCount(60L).build();
        when(historyMapper.selectStatRows(eq("asm_stat_hour"), eq(START), eq(END),
                eq(Arrays.asList(AsmHistoryParamKey.of(UID, "voltage"),
                        AsmHistoryParamKey.of(UID, "current"))),
                eq(AsmIntervalMode.BACK.code()), eq(0), eq(0)))
                .thenReturn(Arrays.asList(r1, r2));
        when(unitContract.resolveUnit(AsmUnitPurpose.STORAGE, UID, "voltage")).thenReturn("V");
        when(unitContract.resolveUnit(AsmUnitPurpose.STORAGE, UID, "current")).thenReturn(null);

        List<SdkStatRow> out = sdk.queryStat(Arrays.asList(key(UID, "voltage"), key(UID, "current")),
                AsmStatGranularity.HOUR, AsmIntervalMode.BACK, START, END);

        assertEquals(2, out.size());
        // 行序保持 SQL 升序透传
        SdkStatRow a = out.get(0);
        assertEquals(UID, a.getLogicDeviceUniqueId());
        assertEquals("voltage", a.getAttrId());
        assertEquals(t1, a.getDataTime());
        assertEquals(220.5, a.getValue());
        assertEquals(58L, a.getValidCount());
        assertEquals(60L, a.getTotalCount());
        assertEquals("V", a.getUnit());
        SdkStatRow b = out.get(1);
        assertEquals("current", b.getAttrId());
        assertEquals(t2, b.getDataTime());
        assertEquals(3.25, b.getValue());
        // STORAGE 行缺失 → null（无量纲语义），非兜底
        assertNull(b.getUnit());
    }

    @Test
    void queryStat_hourWindowOver400Days_throws() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryStat(
                Arrays.asList(key(UID, "voltage")), AsmStatGranularity.HOUR, AsmIntervalMode.BACK,
                START, START.plusSeconds(401L * 24 * 3600)));
    }

    @Test
    void queryStat_nonNumericBucket_mapsValueTextWithNullValue() {
        // 非数值桶（ALARM/STATE series，avgValue null + valueText 非空）：value 不再是空壳行——
        // valueText 原样透传、value=null、unit=seed 空串占位（显无单位），与 listStatParams 目录契约闭合
        AsmHistoryBucket r = AsmHistoryBucket.builder()
                .logicDeviceUniqueId(UID).attrId("water_leak")
                .dataTime(START).valueText("alarm").validCount(3L).totalCount(4L).build();
        when(historyMapper.selectStatRows(eq("asm_stat_hour"), eq(START), eq(END),
                eq(Collections.singletonList(AsmHistoryParamKey.of(UID, "water_leak"))),
                eq(AsmIntervalMode.BACK.code()), eq(0), eq(0)))
                .thenReturn(Collections.singletonList(r));
        when(unitContract.resolveUnit(AsmUnitPurpose.STORAGE, UID, "water_leak")).thenReturn("");

        List<SdkStatRow> out = sdk.queryStat(Collections.singletonList(key(UID, "water_leak")),
                AsmStatGranularity.HOUR, AsmIntervalMode.BACK, START, END);

        assertEquals(1, out.size());
        assertEquals("alarm", out.get(0).getValueText(), "非数值统计值原样透传");
        assertNull(out.get(0).getValue(), "非数值桶无均值");
        assertEquals("", out.get(0).getUnit(), "空串占位=显无单位");
    }

    // ========== listStatParams ==========

    /** 注册带 displayName live attr 的逻辑设备（registry+bus 真实事件链）。 */
    private void registerStationDevice() {
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
        data.put("name", "asm-station");
        entry.setData(data);
        // attrDefs 是 alarm 条目 attrLabel 的解析源（displayName 唯一来源，同生产 LogicDevice 形态）；
        // listStatParams 的 displayName 走 live attrs（device.setAttribute 注入），两路互不影响
        LogicAttributeDefine voltageDef = new LogicAttributeDefine();
        voltageDef.setAttrId("voltage");
        voltageDef.setDisplayName("供电电压");
        List<LogicAttributeDefine> defs = Collections.singletonList(voltageDef);
        LogicDevice device = new LogicDevice(entry) {
            @Override public List<LogicAttributeDefine> getAttrDefs() { return defs; }
            @Override protected String getMappingType() { return "TEST-ASM"; }
            @Override public void start() {}
            @Override public void stop() {}
            @Override public void release() {}
            @Override public Map<String, ILogicAttribute<?>> getAttrMap() { return new HashMap<>(); }
        };
        device.load(com.ecat.core.EcatCore.getInstance());
        device.init();
        // Mockito mock（AttributeBase 非 final）：实现只读 getDisplayName，其余走默认。
        // lenient：displayName 只被 listStatParams（live attrs）路径消费，alarm 条目走 attrDefs 不读它
        AttributeBase<?> voltageAttr = org.mockito.Mockito.mock(AttributeBase.class);
        org.mockito.Mockito.lenient().when(voltageAttr.getAttributeID()).thenReturn("voltage");
        org.mockito.Mockito.lenient().when(voltageAttr.getDisplayName()).thenReturn("供电电压");
        device.setAttribute(voltageAttr);
        registry.register(device.getId(), device);
        busRegistry.publish(BusEvent.of(
                BusTopic.DEVICE_LIFECYCLE.getTopicName(),
                new DeviceLifecycleEvent(device.getId(), "com.ecat:integration-env-air-station-manager",
                        device.getId(), device.getId(), DeviceLifecycleEvent.Action.CREATE),
                EventContext.root(EventContext.Source.SYSTEM, null)));
    }


    @Test
    void listStatParams_emptyRows_returnsEmptyList() {
        when(historyMapper.selectStatParamMetas()).thenReturn(Collections.emptyList());
        assertTrue(sdk.listStatParams().isEmpty());
    }

    @Test
    void listStatParams_multiRows_displayNameResolvedOrDegraded() {
        registerStationDevice();
        when(historyMapper.selectStatParamMetas()).thenReturn(Arrays.asList(
                AsmStatParamMetaRow.builder()
                        .logicDeviceUniqueId(UID).attrId("voltage")
                        .unit("V").granularityMask(7).build(),
                // 注册设备但 attr 不在 live map → displayName 降级 attrId
                AsmStatParamMetaRow.builder()
                        .logicDeviceUniqueId(UID).attrId("unknown")
                        .unit("A").granularityMask(null).build(),
                // 设备不在 registry → attrs 空 Map → 降级 attrId
                AsmStatParamMetaRow.builder()
                        .logicDeviceUniqueId(UID2).attrId("voltage")
                        .unit(null).granularityMask(4).build()));

        List<SdkParamMeta> out = sdk.listStatParams();

        assertEquals(3, out.size());
        SdkParamMeta a = out.get(0);
        assertEquals(UID, a.getLogicDeviceUniqueId());
        assertEquals("voltage", a.getAttrId());
        assertEquals("供电电压", a.getParamDisplayName());
        assertEquals("V", a.getStorageUnit());
        assertEquals(7, a.getApplicableGranularityMask());
        SdkParamMeta b = out.get(1);
        assertEquals("unknown", b.getParamDisplayName());
        assertEquals(0, b.getApplicableGranularityMask());
        SdkParamMeta c = out.get(2);
        assertEquals("voltage", c.getParamDisplayName());
        assertNull(c.getStorageUnit());
        assertEquals(4, c.getApplicableGranularityMask());
    }

    // ========== querySnapshot ==========

    @Test
    void querySnapshot_blankUid_throws() {
        assertThrows(IllegalArgumentException.class, () -> sdk.querySnapshot(null));
        assertThrows(IllegalArgumentException.class, () -> sdk.querySnapshot("  "));
    }

    @Test
    void querySnapshot_emptyAndMultiAttrs_mappingDelegatedToSnapshotService() {
        when(snapshotService.buildForUid(UID)).thenReturn(Collections.emptyList());
        assertTrue(sdk.querySnapshot(UID).isEmpty());

        Instant upd = Instant.parse("2026-08-18T01:02:03Z");
        when(snapshotService.buildForUid(UID)).thenReturn(Arrays.asList(
                AsmSnapshotAttrDto.builder()
                        .attrId("voltage").value(220.5).valueText(null)
                        .unit("V").updateTime(upd).source("LIVE").build(),
                AsmSnapshotAttrDto.builder()
                        .attrId("switch").value(null).valueText("开")
                        .unit(null).updateTime(upd).source("RAW").build()));

        List<SdkSnapshotAttr> out = sdk.querySnapshot(UID);
        assertEquals(2, out.size());
        SdkSnapshotAttr a = out.get(0);
        assertEquals("voltage", a.getAttrId());
        assertEquals(220.5, a.getValue());
        assertNull(a.getValueText());
        assertEquals("V", a.getUnit());
        assertEquals(upd, a.getUpdateTime());
        assertEquals("LIVE", a.getSource());
        SdkSnapshotAttr b = out.get(1);
        assertNull(b.getValue());
        assertEquals("开", b.getValueText());
        assertNull(b.getUnit());
        assertEquals("RAW", b.getSource());
    }

    // ========== queryAlarmEntries ==========

    @Test
    void queryAlarmEntries_multiRows_dualLabelAndDurationDiscipline() {
        // label 双字段复用 AsmDeviceLabelService（REST 出口同源）：th=真槽（静态槽中文名）但设备未注册
        // （attr label null）；UID=注册设备（attr displayName 经 registry 解析）但非槽（device label null）
        registerStationDevice();
        Instant s1 = Instant.parse("2026-08-18T00:10:00Z");
        Instant e1 = Instant.parse("2026-08-18T00:25:00Z");
        when(alarmRecordMapper.selectEntriesByType("room_temp_abnormal", START, END, 50)).thenReturn(Arrays.asList(
                AsmAlarmRecord.builder()
                        .alarmType("room_temp_abnormal").ruleName("设备间温度异常")
                        .logicDeviceUniqueId("logicdevice_station.th")
                        .attrId("temperature").severity("0").startTime(s1).endTime(e1)
                        .description("超上限").status("INACTIVE").resultContent("29>28").build(),
                AsmAlarmRecord.builder()
                        .alarmType("supply_voltage_abnormal").ruleName("供电电源异常波动")
                        .logicDeviceUniqueId(UID)
                        .attrId("voltage").severity("1").startTime(s1).endTime(null)
                        .description("持续中").status("ACTIVE").build()));

        List<SdkAlarmEntry> out = sdk.queryAlarmEntries("room_temp_abnormal", START, END, 50);
        assertEquals(2, out.size());
        SdkAlarmEntry a = out.get(0);
        assertEquals("room_temp_abnormal", a.getAlarmType());
        assertEquals("设备间温度异常", a.getRuleName());
        assertEquals("logicdevice_station.th", a.getLogicDeviceUniqueId());
        assertEquals("站房温湿度监测仪", a.getDeviceLabel(), "真槽静态解析");
        assertEquals("temperature", a.getAttrId());
        assertNull(a.getAttrLabel(), "设备未注册 → attr label=null（回退归消费方）");
        assertEquals(s1, a.getTriggerTime());
        assertEquals(e1, a.getRecoverTime());
        assertEquals(Long.valueOf(15L * 60 * 1000), a.getDurationMs());
        assertEquals("INACTIVE", a.getStatus());
        SdkAlarmEntry b = out.get(1);
        assertEquals("supply_voltage_abnormal", b.getAlarmType());
        assertNull(b.getDeviceLabel(), "非槽 uid → null");
        assertEquals("供电电压", b.getAttrLabel(), "注册设备 attr displayName");
        assertNull(b.getRecoverTime(), "持续中行 recoverTime=null");
        assertNull(b.getDurationMs(), "持续中行不虚构时长");
    }

    // ========== control（null 枚举降 null 分支）==========

    @Test
    void control_nullOriginAndResult_mapsToNull() {
        when(controlService.execute(AsmControlOrigin.LOCAL, "com.ecat:integration-x",
                UID, "temperature", "30.0", null))
                .thenReturn(AsmControlRecord.builder()
                        .id(12L).caller("com.ecat:integration-x")
                        .logicDeviceUniqueId(UID).attrId("temperature")
                        .durationMs(5L).build());

        SdkControlResult out = sdk.control(SdkControlRequest.builder()
                .uid(UID).attrId("temperature").value("30.0").unit("")
                .origin(AsmControlOrigin.LOCAL).caller("com.ecat:integration-x")
                .build());

        assertEquals(Long.valueOf(12L), out.getRecordId());
        assertNull(out.getOrigin());
        assertNull(out.getResult());
        assertNull(out.getError());
        assertEquals(Long.valueOf(5L), out.getDurationMs());
    }
}
