package com.ecat.integration.EnvAirStationManagerIntegration.rule;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 规则索引：复合 key（uid→attrId→rules 嵌套 Map，禁字符串拼接碰撞）+ 坏配置隔离 + 热加载重建。
 */
@ExtendWith(MockitoExtension.class)
class AsmAlarmRuleIndexTest {

    @Mock
    private AsmAlarmRuleMapper ruleMapper;

    private AsmAlarmRuleIndex index;

    @BeforeEach
    void setUp() {
        index = new AsmAlarmRuleIndex(ruleMapper);
    }

    private static AsmAlarmRule row(String alarmType, String severity, String content) {
        return AsmAlarmRule.builder().alarmType(alarmType).severity(severity)
                .settingContent(content).build();
    }

    private static String content(String uid, String attr) {
        return "{\"name\":\"r-" + alarmSeq(uid, attr) + "\",\"enabled\":true,"
                + "\"device_info\":{\"" + uid + "\":[\"" + attr + "\"]}}";
    }

    private static String alarmSeq(String uid, String attr) {
        return uid + "." + attr; // 仅描述文本用，非索引 key
    }

    @Test
    void compositeKey_noStringConcatenationCollision() {
        // 字符串拼接下 "a.b"+"c" 与 "a"+"b.c" 同 key；嵌套 Map 必须分开
        AsmAlarmRule r1 = row("1", "0", content("a.b", "c"));
        AsmAlarmRule r2 = row("2", "0", content("a", "b.c"));
        when(ruleMapper.selectAll()).thenReturn(Arrays.asList(r1, r2));
        index.reload();

        List<AsmAlarmRuleDefinition> slot1 = index.getRules("a.b", "c");
        List<AsmAlarmRuleDefinition> slot2 = index.getRules("a", "b.c");
        assertEquals(1, slot1.size());
        assertEquals(1, slot2.size());
        assertEquals("1", slot1.get(0).getAlarmType());
        assertEquals("2", slot2.get(0).getAlarmType());
        assertTrue(index.getRules("a", "c").isEmpty());
    }

    @Test
    void badRuleIsolated_goodRulesStillIndexed() {
        AsmAlarmRule bad = row("99", "0", "not-json");
        AsmAlarmRule good = row("3", "0",
                "{\"name\":\"漏水\",\"enabled\":true,\"device_info\":{\"u\":[\"water_leak\"]}}");
        when(ruleMapper.selectAll()).thenReturn(Arrays.asList(bad, good));
        index.reload();
        assertEquals(1, index.getRules("u", "water_leak").size());
        assertEquals(1, index.loadedRuleCount());
    }

    @Test
    void hotReload_replacesIndexSnapshot() {
        when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(
                row("1", "0", "{\"name\":\"v1\",\"enabled\":true,\"device_info\":{\"u\":[\"a\"]}}")));
        index.reload();
        assertEquals("0", index.getRules("u", "a").get(0).getSeverity());

        when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(
                row("1", "2", "{\"name\":\"v1\",\"enabled\":true,\"device_info\":{\"u\":[\"a\"]}}")));
        index.reload();
        assertEquals("2", index.getRules("u", "a").get(0).getSeverity());
    }

    @Test
    void reloadWithAllBadRules_leavesIndexEmptyNotThrowing() {
        when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(row("1", "0", "{")));
        index.reload();
        assertTrue(index.getRules("u", "a").isEmpty());
        assertEquals(0, index.loadedRuleCount());
    }

    @Test
    void multiRuleSameSlot_allIndexed() {
        AsmAlarmRule a = row("1", "0", "{\"name\":\"r1\",\"enabled\":true,\"device_info\":{\"u\":[\"a\"]}}");
        AsmAlarmRule b = row("1010", "1", "{\"name\":\"r2\",\"enabled\":true,\"device_info\":{\"u\":[\"a\"]}}");
        when(ruleMapper.selectAll()).thenReturn(Arrays.asList(a, b));
        index.reload();
        assertEquals(2, index.getRules("u", "a").size());
    }
}
