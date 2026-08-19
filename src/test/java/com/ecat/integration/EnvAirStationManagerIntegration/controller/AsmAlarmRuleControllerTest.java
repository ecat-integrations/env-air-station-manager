package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRuleMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报警规则 CRUD + 热加载：每次变更后索引重建（reloadFromMapper 真调 mapper）；
 * 变更入参非法（severity 非法/内容非法）抛 IllegalArgumentException 不落库。
 */
@ExtendWith(MockitoExtension.class)
class AsmAlarmRuleControllerTest {

    @Mock
    private AsmAlarmRuleMapper ruleMapper;
    @Mock
    private AsmAlarmRuleIndex index;

    private AsmAlarmRuleController controller;

    @BeforeEach
    void setUp() {
        controller = new AsmAlarmRuleController(ruleMapper, index);
    }

    private static AsmAlarmRule validRule(String severity) {
        return AsmAlarmRule.builder()
                .alarmType("3").severity(severity)
                .settingContent("{\"name\":\"漏水\",\"enabled\":true,"
                        + "\"device_info\":{\"logicdevice_station.security_alarm\":[\"water_leak\"]}}")
                .sort(1).build();
    }

    @Test
    void add_persistsThenReloadsIndex() {
        when(ruleMapper.insert(any(AsmAlarmRule.class))).thenReturn(1);
        controller.add(validRule("0"));
        verify(ruleMapper).insert(any(AsmAlarmRule.class));
        verify(index).reload();
    }

    @Test
    void edit_persistsThenReloadsIndex() {
        when(ruleMapper.update(any(AsmAlarmRule.class))).thenReturn(1);
        AsmAlarmRule rule = validRule("2");
        rule.setId(9L); // edit 契约要求带 id，否则更新无落点
        controller.edit(rule);
        verify(ruleMapper).update(any(AsmAlarmRule.class));
        verify(index).reload();
    }

    @Test
    void remove_deletesThenReloadsIndex() {
        when(ruleMapper.deleteById(5L)).thenReturn(1);
        controller.remove(5L);
        verify(ruleMapper).deleteById(5L);
        verify(index).reload();
    }

    @Test
    void add_invalidSeverityRejected_noPersistNoReload() {
        org.mockito.Mockito.lenient().when(ruleMapper.insert(any(AsmAlarmRule.class))).thenReturn(1);
        AsmAlarmRule bad = validRule("9");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.add(bad));
        verify(ruleMapper, org.mockito.Mockito.never()).insert(any(AsmAlarmRule.class));
        verify(index, org.mockito.Mockito.never()).reload();
    }

    @Test
    void add_badSettingContentRejected() {
        AsmAlarmRule bad = AsmAlarmRule.builder().alarmType("3").severity("0")
                .settingContent("not-json").build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.add(bad));
        verify(ruleMapper, org.mockito.Mockito.never()).insert(any(AsmAlarmRule.class));
    }

    @Test
    void list_delegatesToMapper() {
        when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(validRule("0")));
        assertEquals(1, controller.list().size());
    }

    @Test
    void getById_delegatesToMapper() {
        when(ruleMapper.selectById(7L)).thenReturn(validRule("0"));
        assertEquals("3", controller.getById(7L).getAlarmType());
    }
}
