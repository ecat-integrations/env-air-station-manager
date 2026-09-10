package com.ecat.integration.EnvAirStationManagerIntegration.consumer;

import com.ecat.core.Bus.event.DeviceDataChangedEvent;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.State.AttrState;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleDefinition;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleEvaluator;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 报警评估 consumer：logicdevice_station.* 过滤 + 逐事件评估 + 触发记录落库（与数据管道 consumer 分离）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmAlarmRuleConsumerTest {

    @Mock
    private DeviceRegistry registry;
    @Mock
    private AsmAlarmRuleEvaluator evaluator;
    @Mock
    private com.ecat.integration.EnvAirStationManagerIntegration.service.AsmAlarmLifecycleService lifecycleService;
    @Mock
    private AsmAlarmRuleIndex ruleIndex;
    @Mock
    private AsmControlService controlService;
    @Mock
    private com.ecat.integration.logicdevice.LogicDevice.LogicDevice stationDevice;
    @Mock
    private com.ecat.integration.logicdevice.LogicDevice.LogicDevice analyzerDevice;
    @Mock
    private com.ecat.integration.logicdevice.LogicDevice.LogicDevice gasDevice;

    private AsmAlarmRuleConsumer consumer;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(stationDevice.getUniqueId()).thenReturn("logicdevice_station.th");
        org.mockito.Mockito.lenient().when(analyzerDevice.getUniqueId()).thenReturn("logicdevice.co");
        org.mockito.Mockito.lenient().when(gasDevice.getUniqueId())
                .thenReturn("logicdevice_station.standard_gas.co");
        when(registry.getDeviceByID(any(String.class)))
                .thenAnswer(inv -> "station-dev".equals(inv.getArgument(0)) ? stationDevice
                        : "analyzer-dev".equals(inv.getArgument(0)) ? analyzerDevice
                        : "gas-dev".equals(inv.getArgument(0)) ? gasDevice : null);
        consumer = new AsmAlarmRuleConsumer("asm-alarm-rule-test", 10, 100, 60_000L,
                registry, evaluator, lifecycleService, ruleIndex, controlService);
    }

    @AfterEach
    void tearDown() {
        consumer.shutdown();
    }

    private static DeviceDataChangedEvent event(String deviceId, String attrId, String displayValue) {
        return new DeviceDataChangedEvent(deviceId, attrId,
                null, stateOf(displayValue, deviceId, attrId));
    }

    /** AttrState 为不可变 final 类（attr-state 契约），禁 mock，构造真实实例。 */
    private static AttrState<Object> stateOf(String displayValue, String deviceId, String attrId) {
        return AttrState.<Object>builder()
                .deviceId(deviceId).attrId(attrId)
                .valueType(Object.class)
                .status(com.ecat.core.State.AttributeStatus.NORMAL)
                .context(com.ecat.core.Bus.event.EventContext.root(
                        com.ecat.core.Bus.event.EventContext.Source.DEVICE_POLL, "test"))
                .displayValue(displayValue)
                .lastUpdated(Instant.parse("2026-08-18T00:00:00Z"))
                .build();
    }

    @Test
    void stationEvent_evaluatedAndRecordInserted() {
        Instant t = Instant.parse("2026-08-18T00:00:00Z");
        AsmAlarmRecord fired = AsmAlarmRecord.builder()
                .alarmType("room_temp_abnormal").ruleName("t").logicDeviceUniqueId("logicdevice_station.th")
                .attrId("temperature").severity("1").startTime(t).endTime(t).description("d")
                .status("0").build();
        when(evaluator.evaluate("logicdevice_station.th", "temperature", "30", t))
                .thenReturn(Collections.singletonList(fired));

        consumer.flush(Collections.singletonList(event("station-dev", "temperature", "30")));

        ArgumentCaptor<AsmAlarmRecord> captor = ArgumentCaptor.forClass(AsmAlarmRecord.class);
        verify(lifecycleService).recordTrigger(captor.capture());
        assertEquals("room_temp_abnormal", captor.getValue().getAlarmType());
    }

    @Test
    void stationEvent_noTrigger_noInsert() {
        Instant t = Instant.parse("2026-08-18T00:00:00Z");
        when(evaluator.evaluate("logicdevice_station.th", "temperature", "20", t))
                .thenReturn(Collections.emptyList());
        consumer.flush(Collections.singletonList(event("station-dev", "temperature", "20")));
        verify(lifecycleService, never()).recordTrigger(any(AsmAlarmRecord.class));
    }

    @Test
    void nonStationEvents_skipped_evaluatorUntouched() {
        consumer.flush(java.util.Arrays.asList(
                event("analyzer-dev", "concentration", "1.0"),   // ADM 分析仪
                event("orphan-dev", "x", "1")));                 // 孤儿事件
        verifyNoInteractions(evaluator);
        verify(lifecycleService, never()).recordTrigger(any(AsmAlarmRecord.class));
    }

    @Test
    void nullDisplayValueEvent_skipped() {
        consumer.flush(Collections.singletonList(event("station-dev", "temperature", null)));
        verifyNoInteractions(evaluator);
    }

    @Test
    void multipleTriggersInBatch_allInsertedInOrder() {
        Instant t = Instant.parse("2026-08-18T00:00:00Z");
        AsmAlarmRecord r1 = AsmAlarmRecord.builder().alarmType("room_temp_abnormal").build();
        AsmAlarmRecord r2 = AsmAlarmRecord.builder().alarmType("water_leak").build();
        when(evaluator.evaluate("logicdevice_station.th", "temperature", "30", t))
                .thenReturn(Arrays.asList(r1, r2));
        consumer.flush(Collections.singletonList(event("station-dev", "temperature", "30")));
        verify(lifecycleService).recordTrigger(r1);
        verify(lifecycleService).recordTrigger(r2);
    }

    // ===== P4 type=8 标气泄漏联动：触发 → 经控制服务以 LOCAL/asm-alarm 写排风扇 =====

    private static com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule ruleRow(
            String settingContent) {
        return com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule.builder()
                .alarmType("gas_leak").severity("1").settingContent(settingContent).build();
    }

    @Test
    void type8Triggered_executesFanControlWithLocalOriginAndAsmAlarmCaller() {
        Instant t = Instant.parse("2026-08-18T00:00:00Z");
        // 修复点3：风扇 uid/attr/值全参数化进规则 configs（setting 型）
        AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(ruleRow(
                "{\"name\":\"标准气体泄漏\",\"enabled\":true,\"configurable\":true,"
                        + "\"device_info\":{\"logicdevice_station.standard_gas.co\":[\"co_concentration\"]},"
                        + "\"configs\":[{\"type\":\"number\",\"class\":\"co_concentration\",\"value\":10},"
                        + "{\"type\":\"setting\",\"device_id\":\"logicdevice_station.exhaust_fan\","
                        + "\"param_id\":\"fan_speed\",\"value\":\"high\"}]}"));
        when(ruleIndex.getRules("logicdevice_station.standard_gas.co", "co_concentration"))
                .thenReturn(Collections.singletonList(def));
        AsmAlarmRecord fired = AsmAlarmRecord.builder()
                .alarmType("gas_leak").ruleName("标准气体泄漏")
                .logicDeviceUniqueId("logicdevice_station.standard_gas.co")
                .attrId("co_concentration").severity("1")
                .startTime(t).endTime(t).description("d").status("0").build();
        when(evaluator.evaluate("logicdevice_station.standard_gas.co", "co_concentration", "15", t))
                .thenReturn(Collections.singletonList(fired));

        consumer.flush(Collections.singletonList(
                event("gas-dev", "co_concentration", "15")));

        verify(controlService).execute(AsmControlOrigin.LOCAL, "asm-alarm",
                "logicdevice_station.exhaust_fan", "fan_speed", "high");
    }

    @Test
    void thresholdNotTriggered_noFanControl() {
        Instant t = Instant.parse("2026-08-18T00:00:00Z");
        when(evaluator.evaluate("logicdevice_station.standard_gas.co", "co_concentration", "5", t))
                .thenReturn(Collections.emptyList());
        consumer.flush(Collections.singletonList(
                event("gas-dev", "co_concentration", "5")));
        verifyNoInteractions(controlService);
    }

    @Test
    void triggeredWithoutLinkageSetting_noFanControl() {
        Instant t = Instant.parse("2026-08-18T00:00:00Z");
        AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(ruleRow(
                "{\"name\":\"标准气体泄漏\",\"enabled\":true,\"configurable\":true,"
                        + "\"device_info\":{\"logicdevice_station.standard_gas.co\":[\"co_concentration\"]},"
                        + "\"configs\":[{\"type\":\"number\",\"class\":\"co_concentration\",\"value\":10}]}"));
        when(ruleIndex.getRules("logicdevice_station.standard_gas.co", "co_concentration"))
                .thenReturn(Collections.singletonList(def));
        AsmAlarmRecord fired = AsmAlarmRecord.builder()
                .alarmType("gas_leak").logicDeviceUniqueId("logicdevice_station.standard_gas.co")
                .attrId("co_concentration").severity("1")
                .startTime(t).endTime(t).description("d").status("0").build();
        when(evaluator.evaluate("logicdevice_station.standard_gas.co", "co_concentration", "15", t))
                .thenReturn(Collections.singletonList(fired));

        consumer.flush(Collections.singletonList(
                event("gas-dev", "co_concentration", "15")));

        verify(lifecycleService).recordTrigger(fired);  // 报警照落
        verifyNoInteractions(controlService);
    }

    @Test
    void fanControlFailure_doesNotKillFlush_alarmStillLanded() {
        Instant t = Instant.parse("2026-08-18T00:00:00Z");
        AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(ruleRow(
                "{\"name\":\"标准气体泄漏\",\"enabled\":true,\"configurable\":true,"
                        + "\"device_info\":{\"logicdevice_station.standard_gas.co\":[\"co_concentration\"]},"
                        + "\"configs\":[{\"type\":\"number\",\"class\":\"co_concentration\",\"value\":10},"
                        + "{\"type\":\"setting\",\"device_id\":\"logicdevice_station.exhaust_fan\","
                        + "\"param_id\":\"fan_speed\",\"value\":\"high\"}]}"));
        when(ruleIndex.getRules("logicdevice_station.standard_gas.co", "co_concentration"))
                .thenReturn(Collections.singletonList(def));
        AsmAlarmRecord fired = AsmAlarmRecord.builder()
                .alarmType("gas_leak").logicDeviceUniqueId("logicdevice_station.standard_gas.co")
                .attrId("co_concentration").severity("1")
                .startTime(t).endTime(t).description("d").status("0").build();
        when(evaluator.evaluate("logicdevice_station.standard_gas.co", "co_concentration", "15", t))
                .thenReturn(Collections.singletonList(fired));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("未知站房逻辑设备"))
                .when(controlService).execute(any(), any(), any(), any(), any());

        consumer.flush(Collections.singletonList(
                event("gas-dev", "co_concentration", "15")));

        verify(lifecycleService).recordTrigger(fired);  // 联动失败不拖累报警落库
    }
}
