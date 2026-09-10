package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmAlarmRecordRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.ruoyi.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 报警记录列表端点：时间窗/uid/status 过滤解析 + 分页缺省（机制同 AsmControlRecordControllerTest）；
 * 行追加 ruoyi 化契约字段：device_label/attr_label（mock registry 解析+回退）、
 * trigger_time（=start_time）/recover_time（=end_time）/duration_ms（活跃行 null），
 * 序列化平铺契约（旧字段路径不变 + snake_case 新键、无 record 包裹层）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmAlarmRecordControllerTest {

    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    @Mock
    private AsmAlarmRecordMapper recordMapper;

    private AsmAlarmRecordController controller;

    @BeforeEach
    void setUp() {
        // 空 registry 桩：label 走回退（解析用例在 list_appendsContractFields 注入 mock registry）
        controller = new AsmAlarmRecordController(recordMapper, new AsmDeviceLabelService(uid -> null));
    }

    @Test
    void list_appendsContractFields_perRow() {
        Instant start = LocalDateTime.parse("2026-08-15T00:00:00").atZone(JVM_ZONE).toInstant();
        Instant end = LocalDateTime.parse("2026-08-15T23:00:00").atZone(JVM_ZONE).toInstant();
        Instant t1 = Instant.parse("2026-08-15T01:00:00Z");
        Instant t2 = Instant.parse("2026-08-15T01:05:00Z");
        AsmAlarmRecord closed = AsmAlarmRecord.builder()
                .id(1L).alarmType("gas_cylinder_low").ruleName("标准气体更换")
                .logicDeviceUniqueId("logicdevice_station.standard_gas.co")
                .attrId("gas_pressure_remaining")
                .startTime(t1).endTime(t2).status("INACTIVE").build();
        AsmAlarmRecord active = AsmAlarmRecord.builder()
                .id(2L).alarmType("room_temp_abnormal").ruleName("设备间温度异常")
                .logicDeviceUniqueId("logicdevice_station.th")
                .attrId("temperature")
                .startTime(t1).status("ACTIVE").build();
        when(recordMapper.countList(isNull(String.class), isNull(String.class), eq(start), eq(end))).thenReturn(2L);
        when(recordMapper.selectList(isNull(String.class), isNull(String.class), eq(start), eq(end), eq(50)))
                .thenReturn(Arrays.asList(closed, active));

        // registry 桩：th 有 temperature def；standard_gas.co 无设备（attr 回退 attrId；槽中文静态可解析）
        LogicDevice th = mock(LogicDevice.class, withSettings().lenient());
        LogicAttributeDefine def = new LogicAttributeDefine();
        def.setAttrId("temperature");
        def.setDisplayName("温度");
        when(th.getAttrDefs()).thenReturn(Collections.singletonList(def));
        Map<String, LogicDevice> registry = new HashMap<>();
        registry.put("logicdevice_station.th", th);
        AsmAlarmRecordController controllerWithRegistry =
                new AsmAlarmRecordController(recordMapper, new AsmDeviceLabelService(registry::get));

        AjaxResult result = controllerWithRegistry.list("2026-08-15T00:00:00", "2026-08-15T23:00:00",
                null, null, null, null);

        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(2L, data.get("total"));
        List<?> rows = (List<?>) data.get("rows");
        assertEquals(2, rows.size());

        AsmAlarmRecordRowDto closedRow = (AsmAlarmRecordRowDto) rows.get(0);
        assertEquals("CO标气", closedRow.getDeviceLabel());
        assertEquals("gas_pressure_remaining", closedRow.getAttrLabel()); // registry 无设备 → attrId 原文
        assertEquals(t1, closedRow.getTriggerTime());
        assertEquals(t2, closedRow.getRecoverTime());
        assertEquals(Duration.between(t1, t2).toMillis(), closedRow.getDurationMs().longValue());

        AsmAlarmRecordRowDto activeRow = (AsmAlarmRecordRowDto) rows.get(1);
        assertEquals("站房温湿度监测仪", activeRow.getDeviceLabel());
        assertEquals("温度", activeRow.getAttrLabel());
        assertEquals(t1, activeRow.getTriggerTime());
        assertNull(activeRow.getRecoverTime(), "活跃行未恢复，recover_time=null");
        assertNull(activeRow.getDurationMs(), "活跃行无终止时刻，duration_ms=null 不猜");
    }

    @Test
    void rowDto_serializesFlattenedContractKeys() throws Exception {
        // 平铺契约：旧字段（alarmType/startTime...）保留原路径，新键 snake_case，不产生 record 包裹层
        AsmAlarmRecord record = AsmAlarmRecord.builder()
                .id(7L).alarmType("room_temp_abnormal").ruleName("设备间温度异常")
                .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                .startTime(Instant.parse("2026-08-15T01:00:00Z")).status("ACTIVE").build();
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(AsmAlarmRecordRowDto.of(record, "站房温湿度监测仪", "温度"));

        assertTrue(json.contains("\"device_label\""), json);
        assertTrue(json.contains("\"attr_label\""), json);
        assertTrue(json.contains("\"trigger_time\""), json);
        assertTrue(json.contains("\"recover_time\""), json);
        assertTrue(json.contains("\"duration_ms\""), json);
        assertTrue(json.contains("\"alarmType\""), "旧字段路径保留（平铺）: " + json);
        assertTrue(json.contains("\"startTime\""), "旧字段路径保留（平铺）: " + json);
        assertFalse(json.contains("\"record\""), "不得出现 record 包裹层: " + json);
        assertNull(AsmAlarmRecordRowDto.durationMs(record), "活跃行 duration 派生 null");
    }

    @Test
    void list_invalidStatusRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.list("2026-08-15T00:00:00", "2026-08-15T23:00:00",
                        null, "BOGUS", null, null));
    }

    @Test
    void list_startMustPrecedeEnd() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.list("2026-08-15T23:00:00", "2026-08-15T00:00:00",
                        null, null, null, null));
    }

    @Test
    void list_zeroTotalSkipsRowQuery() {
        Instant start = LocalDateTime.parse("2026-08-15T00:00:00").atZone(JVM_ZONE).toInstant();
        Instant end = LocalDateTime.parse("2026-08-15T01:00:00").atZone(JVM_ZONE).toInstant();
        when(recordMapper.countList(isNull(String.class), isNull(String.class), eq(start), eq(end)))
                .thenReturn(0L);
        AjaxResult result = controller.list("2026-08-15T00:00:00", "2026-08-15T01:00:00",
                null, null, null, null);
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(0L, data.get("total"));
        assertEquals(0, ((List<?>) data.get("rows")).size());
    }
}
