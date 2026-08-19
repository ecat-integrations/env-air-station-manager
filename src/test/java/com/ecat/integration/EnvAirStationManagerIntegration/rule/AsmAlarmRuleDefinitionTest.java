package com.ecat.integration.EnvAirStationManagerIntegration.rule;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 规则行 → 解析后规则定义（坏配置解析期抛明确异常，由索引层隔离跳过）。
 */
class AsmAlarmRuleDefinitionTest {

    private static AsmAlarmRule row(String alarmType, String severity, String content) {
        return AsmAlarmRule.builder()
                .alarmType(alarmType).severity(severity).settingContent(content).build();
    }

    @Test
    void parse_rangeDurationInferredFromConfigs() {
        String json = "{\"name\":\"设备间温度异常\",\"enabled\":true,\"configurable\":true,"
                + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]},"
                + "\"configs\":[{\"type\":\"range\",\"value\":[18,28]},{\"type\":\"duration\",\"value\":5}]}";
        AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(row("1", "1", json));
        assertEquals(AsmAlarmCheckType.RANGE_DURATION, def.getCheck());
        assertEquals("1", def.getAlarmType());
        assertEquals("1", def.getSeverity());
        assertEquals(18.0, def.getRangeMin());
        assertEquals(28.0, def.getRangeMax());
        assertEquals(5L, def.getDurationMinutes());
        assertNotNull(def.getDevices().get("logicdevice_station.th"));
    }

    @Test
    void parse_explicitPowerCheckWithThreshold() {
        String json = "{\"name\":\"断电和恢复报警\",\"enabled\":true,"
                + "\"device_info\":{\"logicdevice_station.power_meter\":[\"voltage_a\"]},"
                + "\"configs\":[{\"type\":\"number\",\"class\":\"power\",\"value\":100}]}";
        AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(row("15", "2", json));
        assertEquals(AsmAlarmCheckType.POWER, def.getCheck());
        assertEquals(100.0, def.getThresholdFor("power"));
    }

    @Test
    void parse_instantThresholdPerAttrAndDirection() {
        String json = "{\"name\":\"标气更换\",\"enabled\":true,\"configurable\":true,"
                + "\"device_info\":{\"logicdevice_station.standard_gas.co\":[\"gas_pressure_remaining\"]},"
                + "\"configs\":[{\"type\":\"number\",\"class\":\"gas_pressure_remaining\",\"value\":10,\"compare\":\"lt\"}]}";
        AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(row("9", "1", json));
        assertEquals(AsmAlarmCheckType.INSTANT_THRESHOLD, def.getCheck());
        assertEquals("lt", def.getCompareFor("gas_pressure_remaining"));
        assertEquals(10.0, def.getThresholdFor("gas_pressure_remaining"));
    }

    @Test
    void parse_statusMatchDefaultsAndCustomContains() {
        String plain = "{\"name\":\"设备间漏水\",\"enabled\":true,"
                + "\"device_info\":{\"logicdevice_station.security_alarm\":[\"water_leak\"]}}";
        assertEquals(AsmAlarmCheckType.STATUS_MATCH,
                AsmAlarmRuleDefinition.parse(row("3", "0", plain)).getCheck());

        String contains = "{\"name\":\"门禁异常\",\"enabled\":true,"
                + "\"device_info\":{\"logicdevice_station.door\":[\"event\"]},"
                + "\"configs\":[{\"type\":\"status\",\"value\":[\"失败\",\"超时\"],\"match\":\"contains\"}]}";
        AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(row("22", "0", contains));
        assertEquals(AsmAlarmCheckType.STATUS_MATCH, def.getCheck());
        assertEquals("contains", def.getStatusMatchMode());
        assertEquals(2, def.getStatusMatchValues().size());
    }

    @Test
    void parse_severityDefaultedAndValidated() {
        String json = "{\"name\":\"x\",\"enabled\":true,\"device_info\":{\"u\":[\"a\"]}}";
        assertEquals("0", AsmAlarmRuleDefinition.parse(row("3", null, json)).getSeverity());
        assertThrows(IllegalArgumentException.class,
                () -> AsmAlarmRuleDefinition.parse(row("3", "9", json)));
    }

    @Test
    void parse_badConfigsThrowExplicit() {
        AsmAlarmRule notJson = row("1", "0", "not-a-json");
        assertThrows(Exception.class, () -> AsmAlarmRuleDefinition.parse(notJson));

        AsmAlarmRule noDevices = row("1", "0", "{\"name\":\"x\",\"enabled\":true}");
        assertThrows(IllegalArgumentException.class, () -> AsmAlarmRuleDefinition.parse(noDevices));

        AsmAlarmRule noName = row("1", "0", "{\"enabled\":true,\"device_info\":{\"u\":[\"a\"]}}");
        assertThrows(IllegalArgumentException.class, () -> AsmAlarmRuleDefinition.parse(noName));

        AsmAlarmRule unknownCheck = row("1", "0",
                "{\"name\":\"x\",\"enabled\":true,\"check\":\"magic\",\"device_info\":{\"u\":[\"a\"]}}");
        assertThrows(IllegalArgumentException.class, () -> AsmAlarmRuleDefinition.parse(unknownCheck));

        AsmAlarmRule badRange = row("1", "0", "{\"name\":\"x\",\"enabled\":true,"
                + "\"device_info\":{\"u\":[\"a\"]},\"configs\":[{\"type\":\"range\",\"value\":[10]}]}");
        assertThrows(IllegalArgumentException.class, () -> AsmAlarmRuleDefinition.parse(badRange));
    }

    /** 规则行 setting_content 为 null 也属坏配置（明确异常非 NPE 语义）。 */
    @Test
    void parse_nullContentThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AsmAlarmRuleDefinition.parse(row("1", "0", null)));
    }

    /** 截断 JSON 解析失败抛 IllegalArgumentException（统一异常口径，索引层按坏配置隔离）。 */
    @Test
    void parse_truncatedJsonThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> AsmAlarmRuleDefinition.parse(row("1", "0", "{")));
    }
}
