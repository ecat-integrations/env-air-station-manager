package com.ecat.integration.EnvAirStationManagerIntegration.rule;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三类判定 + 去重窗口 + 断电恢复 + 坏规则运行时隔离。时间源注入 MutableClock（禁 sleep）。
 */
class AsmAlarmRuleEvaluatorTest {

    /** 手动推进的时钟（虚拟时间源）。 */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void set(Instant t) { this.now = t; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final String UID = "logicdevice_station.th";

    private MutableClock clock;
    private AsmAlarmRuleEvaluator evaluator;
    private AsmAlarmRuleIndex index;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        index = new AsmAlarmRuleIndex(null); // 测试直接 install 定义，不经 mapper
        evaluator = new AsmAlarmRuleEvaluator(index);
        evaluator.setClock(clock);
    }

    private void install(String alarmType, String severity, String content) {
        index.install(java.util.Collections.singletonList(
                AsmAlarmRuleDefinition.parse(com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule.builder()
                        .alarmType(alarmType).severity(severity).settingContent(content).build())));
    }

    private static final String RANGE_JSON = "{\"name\":\"设备间温度异常\",\"enabled\":true,\"configurable\":true,"
            + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]},"
            + "\"configs\":[{\"type\":\"range\",\"value\":[18,28]},{\"type\":\"duration\",\"value\":5}]}";

    private List<AsmAlarmRecord> eval(double value, Instant eventTime) {
        return evaluator.evaluate(UID, "temperature", String.valueOf(value), eventTime);
    }

    // ===== ① range + duration =====

    @Test
    void rangeDuration_sustainedExceedFiresWithSeverityFromRule() {
        install("1", "1", RANGE_JSON);
        assertTrue(eval(30, T0).isEmpty());                       // 起算
        clock.set(T0.plusSeconds(6 * 60));
        List<AsmAlarmRecord> fired = eval(30, T0.plusSeconds(6 * 60));
        assertEquals(1, fired.size());
        AsmAlarmRecord rec = fired.get(0);
        assertEquals("1", rec.getAlarmType());
        assertEquals("设备间温度异常", rec.getRuleName());
        assertEquals("1", rec.getSeverity());                     // 修复点4：severity 来自规则
        assertEquals(UID, rec.getLogicDeviceUniqueId());
        assertEquals("temperature", rec.getAttrId());
        assertEquals(T0, rec.getStartTime());
        // 心跳窗模型：ACTIVE 行 end_time=null（活跃中无结束时刻），last_breach_time=本次命中
        assertNull(rec.getEndTime());
        assertEquals(T0.plusSeconds(6 * 60), rec.getLastBreachTime());
        assertEquals("ACTIVE", rec.getStatus());
        assertNotNull(rec.getResultContent());
    }

    @Test
    void rangeDuration_notSustainedDoesNotFire() {
        install("1", "0", RANGE_JSON);
        assertTrue(eval(30, T0).isEmpty());
        clock.set(T0.plusSeconds(3 * 60));
        assertTrue(eval(30, T0.plusSeconds(3 * 60)).isEmpty());   // 3min < 5min
    }

    @Test
    void rangeDuration_recoveryClearsDurationTimer() {
        install("1", "0", "{\"name\":\"t\",\"enabled\":true,\"configurable\":true,"
                + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]},"
                + "\"configs\":[{\"type\":\"range\",\"value\":[18,28]},{\"type\":\"duration\",\"value\":2}]}");
        assertTrue(eval(30, T0).isEmpty());                       // 起算
        clock.set(T0.plusSeconds(60));
        assertTrue(eval(20, T0.plusSeconds(60)).isEmpty());       // 恢复 → 清计时
        clock.set(T0.plusSeconds(120));
        assertTrue(eval(30, T0.plusSeconds(120)).isEmpty());      // 重新起算（start=+120s）
        clock.set(T0.plusSeconds(240));
        List<AsmAlarmRecord> fired = eval(30, T0.plusSeconds(240));
        assertEquals(1, fired.size());
        assertEquals(T0.plusSeconds(120), fired.get(0).getStartTime()); // 若未清除则 start=T0
    }

    // ===== 心跳窗（替代旧 5min 去重：每次命中都产出记录，续期/新插归生命周期层）=====

    @Test
    void heartbeat_everyHitProducesTrigger_noInMemorySuppression() {
        // 瞬时阈值规则逐事件触发：窗口内重复命中不再被内存去重抑制（续期由 AsmAlarmLifecycleService 收口）
        install("23", "0", "{\"name\":\"t\",\"enabled\":true,\"configurable\":true,"
                + "\"device_info\":{\"logicdevice_station.th\":[\"pm10_indoor\"]},"
                + "\"configs\":[{\"type\":\"number\",\"class\":\"pm10_indoor\",\"value\":100}]}");
        clock.set(T0);
        assertEquals(1, evaluator.evaluate(UID, "pm10_indoor", "150", T0).size());
        clock.set(T0.plusSeconds(120));
        List<AsmAlarmRecord> again = evaluator.evaluate(UID, "pm10_indoor", "150", T0.plusSeconds(120));
        assertEquals(1, again.size());                              // 命中即产出（生命周期层 extendActive 续期）
        assertEquals(T0.plusSeconds(120), again.get(0).getLastBreachTime());
        assertEquals("ACTIVE", again.get(0).getStatus());
    }

    @Test
    void dedupWindow_keyIncludesAttrAndRule() {
        // 同 uid 不同 attr 的两条规则各自独立去重（修复点1 复合 key 语义）
        index.install(java.util.Arrays.asList(
                AsmAlarmRuleDefinition.parse(com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule.builder()
                        .alarmType("room_temp_abnormal").severity("0").settingContent(
                                "{\"name\":\"t1\",\"enabled\":true,\"configurable\":true,"
                                + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]},"
                                + "\"configs\":[{\"type\":\"number\",\"class\":\"temperature\",\"value\":28}]}")
                        .build()),
                AsmAlarmRuleDefinition.parse(com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule.builder()
                        .alarmType("range_exceeded").severity("0").settingContent(
                                "{\"name\":\"t2\",\"enabled\":true,\"configurable\":true,"
                                + "\"device_info\":{\"logicdevice_station.th\":[\"humidity\"]},"
                                + "\"configs\":[{\"type\":\"number\",\"class\":\"humidity\",\"value\":70}]}")
                        .build())));
        clock.set(T0);
        assertEquals(1, eval(30, T0).size());
        List<AsmAlarmRecord> other = evaluator.evaluate(UID, "humidity", "80", T0);
        assertEquals(1, other.size());                             // 不同 attr 不被前者去重误伤
    }

    // ===== ② 瞬时阈值 =====

    @Test
    void instantThreshold_aboveFires_andBelowDirectionFires() {
        install("23", "1", "{\"name\":\"洁净度\",\"enabled\":true,\"configurable\":true,"
                + "\"device_info\":{\"logicdevice_station.th\":[\"pm10_indoor\"]},"
                + "\"configs\":[{\"type\":\"number\",\"class\":\"pm10_indoor\",\"value\":100}]}");
        clock.set(T0);
        assertEquals(0, evaluator.evaluate(UID, "pm10_indoor", "99", T0).size());
        List<AsmAlarmRecord> fired = evaluator.evaluate(UID, "pm10_indoor", "101", T0);
        assertEquals(1, fired.size());
        assertEquals("1", fired.get(0).getSeverity());

        install("9", "2", "{\"name\":\"标气\",\"enabled\":true,\"configurable\":true,"
                + "\"device_info\":{\"logicdevice_station.th\":[\"gas_pressure_remaining\"]},"
                + "\"configs\":[{\"type\":\"number\",\"class\":\"gas_pressure_remaining\",\"value\":10,\"compare\":\"lt\"}]}");
        List<AsmAlarmRecord> below = evaluator.evaluate(UID, "gas_pressure_remaining", "5", T0);
        assertEquals(1, below.size());
        assertEquals("2", below.get(0).getSeverity());
    }

    // ===== ③ 状态串 =====

    @Test
    void statusMatch_defaultEqualsSetAndCustomContains() {
        install("3", "0", "{\"name\":\"漏水\",\"enabled\":true,"
                + "\"device_info\":{\"logicdevice_station.security_alarm\":[\"water_leak\"]}}");
        clock.set(T0);
        assertEquals(0, evaluator.evaluate("logicdevice_station.security_alarm", "water_leak", "正常", T0).size());
        assertEquals(1, evaluator.evaluate("logicdevice_station.security_alarm", "water_leak", "报警", T0).size());

        install("22", "0", "{\"name\":\"门禁\",\"enabled\":true,"
                + "\"device_info\":{\"logicdevice_station.door\":[\"event\"]},"
                + "\"configs\":[{\"type\":\"status\",\"value\":[\"失败\",\"超时\"],\"match\":\"contains\"}]}");
        assertEquals(1, evaluator.evaluate("logicdevice_station.door", "event", "刷卡失败3次", T0).size());
        assertEquals(0, evaluator.evaluate("logicdevice_station.door", "event", "刷卡成功", T0).size());
    }

    // ===== 断电 + 恢复（保留恢复语义，force 不走去重）=====

    @Test
    void powerFailure_firesOnBelowAndForcesRecoveryRecord() {
        install("15", "2", "{\"name\":\"断电\",\"enabled\":true,"
                + "\"device_info\":{\"logicdevice_station.power_meter\":[\"voltage_a\"]},"
                + "\"configs\":[{\"type\":\"number\",\"class\":\"power\",\"value\":100}]}");
        clock.set(T0);
        assertTrue(evaluator.evaluate("logicdevice_station.power_meter", "voltage_a", "220", T0).isEmpty());

        List<AsmAlarmRecord> outage = evaluator.evaluate("logicdevice_station.power_meter", "voltage_a", "80", T0);
        assertEquals(1, outage.size());
        assertTrue(outage.get(0).getDescription().contains("断电"));

        // 紧接着恢复（同一去重窗口内）→ force 记录恢复，不被去重抑制
        clock.set(T0.plusSeconds(30));
        List<AsmAlarmRecord> recovery = evaluator.evaluate("logicdevice_station.power_meter", "voltage_a", "220",
                T0.plusSeconds(30));
        assertEquals(1, recovery.size());
        assertTrue(recovery.get(0).getDescription().contains("恢复"));
        // 恢复行是终态记录：INACTIVE + end_time=恢复时刻 + recovery 标记（生命周期层据此闭 ACTIVE 行）
        assertEquals("INACTIVE", recovery.get(0).getStatus());
        assertEquals(T0.plusSeconds(30), recovery.get(0).getEndTime());
        assertTrue(recovery.get(0).isRecovery());

        // 恢复只发一次：再上报正常电压不再发
        clock.set(T0.plusSeconds(60));
        assertTrue(evaluator.evaluate("logicdevice_station.power_meter", "voltage_a", "220",
                T0.plusSeconds(60)).isEmpty());
    }

    // ===== 坏规则运行时隔离 + 开关 =====

    @Test
    void runtimeBadRuleIsolated_otherRulesStillEvaluated() {
        // 同 series 两条规则：r1 数值规则遇非数值 displayValue（运行时错误跳过）；r2 状态串规则照常评估命中
        index.install(java.util.Arrays.asList(
                AsmAlarmRuleDefinition.parse(com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule.builder()
                        .alarmType("room_temp_abnormal").severity("1").settingContent(RANGE_JSON).build()),
                AsmAlarmRuleDefinition.parse(com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule.builder()
                        .alarmType("access_control_abnormal").severity("2").settingContent(
                                "{\"name\":\"门禁\",\"enabled\":true,"
                                + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]},"
                                + "\"configs\":[{\"type\":\"status\",\"value\":[\"abc\"],\"match\":\"equals\"}]}")
                        .build())));
        clock.set(T0);
        List<AsmAlarmRecord> fired = evaluator.evaluate(UID, "temperature", "abc", T0);
        assertEquals(1, fired.size());                             // 坏规则跳过，好规则照常
        assertEquals("access_control_abnormal", fired.get(0).getAlarmType());
    }

    @Test
    void disabledRuleNeverFires() {
        install("1", "0", "{\"name\":\"t\",\"enabled\":false,\"configurable\":true,"
                + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]},"
                + "\"configs\":[{\"type\":\"range\",\"value\":[18,28]},{\"type\":\"duration\",\"value\":1}]}");
        clock.set(T0);
        assertEquals(0, eval(30, T0).size());
        clock.set(T0.plusSeconds(120));
        assertEquals(0, eval(30, T0.plusSeconds(120)).size());
    }

    @Test
    void nullDisplayValueSkipped() {
        install("1", "0", RANGE_JSON);
        assertTrue(evaluator.evaluate(UID, "temperature", null, T0).isEmpty());
    }
}
