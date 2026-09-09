package com.ecat.integration.EnvAirStationManagerIntegration.api;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AirStationSdkImpl;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSnapshotService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SDK queryAlarmEntries/listAlarmTypes：入参严格校验 + episode 重叠出口委派 + 表格对齐行组装
 * + 目录映射与坏行隔离。SQL 窗口语义（左开右闭谓词）由 AsmAlarmRecordMapperSqlShapeTest 锁定。
 */
@ExtendWith(MockitoExtension.class)
class AsmAlarmSdkQueryTest {

    private static final String UID = "logicdevice_station.security_alarm";

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private AsmSnapshotService snapshotService;
    @Mock
    private AsmAlarmRecordMapper recordMapper;
    @Mock
    private AsmControlService controlService;
    @Mock
    private AsmDeviceLabelService labelService;

    private AirStationSdkImpl sdk;

    private static final Instant START = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-19T00:00:00Z");

    @BeforeEach
    void setUp() {
        sdk = new AirStationSdkImpl(historyMapper, unitContract, snapshotService,
                recordMapper, labelService, controlService);
    }

    // ===== queryAlarmEntries =====

    @Test
    void query_ongoingAlarmStartedBeforeWindow_mustReturn() {
        // 需求靶点：窗前触发、窗后仍未恢复（end_time=null 的 ACTIVE 行）必须返回——
        // 旧 queryAlarmRecords 锚 end_time 落窗永远查不出持续中；新查询 episode 重叠出口天然承载
        Instant firedBeforeWindow = START.minusSeconds(3600);
        AsmAlarmRecord ongoing = AsmAlarmRecord.builder()
                .id(1L).alarmType("water_leak").ruleName("设备间漏水")
                .logicDeviceUniqueId(UID).attrId("water_leak")
                .severity("0").startTime(firedBeforeWindow).endTime(null)
                .description("水浸传感器报警").status("ACTIVE").lastBreachTime(END.minusSeconds(60)).build();
        when(recordMapper.selectEntriesByType("water_leak", START, END, 500))
                .thenReturn(Collections.singletonList(ongoing));

        List<SdkAlarmEntry> out = sdk.queryAlarmEntries("water_leak", START, END, 500);

        assertEquals(1, out.size());
        SdkAlarmEntry e = out.get(0);
        assertEquals("ACTIVE", e.getStatus());
        assertEquals(firedBeforeWindow, e.getTriggerTime());
        assertNull(e.getRecoverTime(), "持续中行 recoverTime=null");
        assertNull(e.getDurationMs(), "持续中行不虚构时长");
    }

    @Test
    void query_rowShapeMatchesFrontendTableColumnsPlusTypeEcho() {
        // 行形状契约 = 前端报警表格九列 + alarmType 查询键回显；闭单行 durationMs=end-start 毫秒
        Instant s = Instant.parse("2026-08-18T01:00:00Z");
        Instant r = Instant.parse("2026-08-18T01:25:00Z");
        AsmAlarmRecord closed = AsmAlarmRecord.builder()
                .id(2L).alarmType("water_leak").ruleName("设备间漏水")
                .logicDeviceUniqueId(UID).attrId("water_leak")
                .severity("1").startTime(s).endTime(r)
                .description("水浸传感器报警").status("INACTIVE").build();
        when(labelService.slotLabelOrNull(UID)).thenReturn("水浸烟感报警器");
        when(labelService.attrLabelOrNull(UID, "water_leak")).thenReturn("漏水状态");
        when(recordMapper.selectEntriesByType("water_leak", START, END, 10))
                .thenReturn(Collections.singletonList(closed));

        List<SdkAlarmEntry> out = sdk.queryAlarmEntries("water_leak", START, END, 10);

        assertEquals(1, out.size());
        SdkAlarmEntry e = out.get(0);
        assertEquals("water_leak", e.getAlarmType(), "查询键回显（消费方对账）");
        assertEquals("设备间漏水", e.getRuleName());
        assertEquals(UID, e.getLogicDeviceUniqueId());
        assertEquals("水浸烟感报警器", e.getDeviceLabel());
        assertEquals("water_leak", e.getAttrId());
        assertEquals("漏水状态", e.getAttrLabel());
        assertEquals("1", e.getSeverity());
        assertEquals("INACTIVE", e.getStatus());
        assertEquals(s, e.getTriggerTime());
        assertEquals(r, e.getRecoverTime());
        assertEquals(Long.valueOf((25L * 60) * 1000L), e.getDurationMs());
        assertEquals("水浸传感器报警", e.getDescription(), "原文透传（label 重组是消费方展示逻辑）");
    }

    @Test
    void query_labelsUnresolved_nullNotInlineFallback() {
        // 机对机口径：label 解析不到=null（回退 uid/attrId 留给消费方），行内标识字段仍携带
        AsmAlarmRecord row = AsmAlarmRecord.builder()
                .alarmType("water_leak").ruleName("设备间漏水")
                .logicDeviceUniqueId(UID).attrId("water_leak")
                .severity("0").startTime(START).status("ACTIVE").build();
        lenient().when(labelService.slotLabelOrNull(UID)).thenReturn(null);
        lenient().when(labelService.attrLabelOrNull(UID, "water_leak")).thenReturn(null);
        when(recordMapper.selectEntriesByType("water_leak", START, END, 10))
                .thenReturn(Collections.singletonList(row));

        SdkAlarmEntry e = sdk.queryAlarmEntries("water_leak", START, END, 10).get(0);

        assertNull(e.getDeviceLabel(), "槽解析不到=null，不内联回退 uid");
        assertNull(e.getAttrLabel(), "attr 解析不到=null，不内联回退 attrId");
        assertEquals(UID, e.getLogicDeviceUniqueId(), "标识字段仍携带（消费方回退依据）");
        assertEquals("water_leak", e.getAttrId());
    }

    @Test
    void query_emptyOrNullRows_returnsEmptyList() {
        when(recordMapper.selectEntriesByType("water_leak", START, END, 100))
                .thenReturn(Collections.emptyList());
        assertEquals(0, sdk.queryAlarmEntries("water_leak", START, END, 100).size());

        when(recordMapper.selectEntriesByType("water_leak", START, END, 100)).thenReturn(null);
        assertEquals(0, sdk.queryAlarmEntries("water_leak", START, END, 100).size());
    }

    @Test
    void query_strictValidation() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmEntries(null, START, END, 10));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmEntries(" ", START, END, 10)); // blank
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmEntries("water_leak", null, END, 10));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmEntries("water_leak", START, null, 10));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmEntries("water_leak", END, START, 10)); // start>=end
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmEntries("water_leak", START, END, 0));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmEntries("water_leak", START, END, 1001));
    }

    // ===== listAlarmTypes =====

    @Test
    void listAlarmTypes_mapsCatalogRows() {
        AsmAlarmRule row = AsmAlarmRule.builder()
                .alarmType("water_leak").severity("1")
                .settingContent("{\"name\":\"设备间漏水\",\"enabled\":true,"
                        + "\"device_info\":{\"logicdevice_station.security_alarm\":[\"water_leak\"]}}")
                .build();
        when(recordMapper.selectTypeCatalog()).thenReturn(Collections.singletonList(row));

        List<SdkAlarmTypeMeta> out = sdk.listAlarmTypes();

        assertEquals(1, out.size());
        assertEquals("water_leak", out.get(0).getAlarmType());
        assertEquals("设备间漏水", out.get(0).getRuleName());
        assertEquals("1", out.get(0).getSeverity());
    }

    @Test
    void listAlarmTypes_badRowIsolated_goodRowsKept() {
        // 坏配置行隔离跳过（同 AsmAlarmRuleIndex 坏行语义）：目录只列合法标识，单条坏不毒死整个目录
        AsmAlarmRule bad = AsmAlarmRule.builder()
                .alarmType("broken").severity("0").settingContent("not-a-json").build();
        AsmAlarmRule good = AsmAlarmRule.builder()
                .alarmType("room_temp_abnormal").severity("2")
                .settingContent("{\"name\":\"设备间温度异常\",\"enabled\":true,"
                        + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]}}")
                .build();
        when(recordMapper.selectTypeCatalog()).thenReturn(Arrays.asList(bad, good));

        List<SdkAlarmTypeMeta> out = sdk.listAlarmTypes();

        assertEquals(1, out.size());
        assertEquals("room_temp_abnormal", out.get(0).getAlarmType());
    }

    @Test
    void listAlarmTypes_emptyOrNullRows_returnsEmptyList() {
        when(recordMapper.selectTypeCatalog()).thenReturn(Collections.emptyList());
        assertEquals(0, sdk.listAlarmTypes().size());

        when(recordMapper.selectTypeCatalog()).thenReturn(null);
        assertEquals(0, sdk.listAlarmTypes().size());
    }
}
