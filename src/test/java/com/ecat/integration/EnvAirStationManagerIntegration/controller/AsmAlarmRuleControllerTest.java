package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmAlarmRuleRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmDeviceLabelsDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRuleMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 报警规则 CRUD + 热加载：每次变更后索引重建（reloadFromMapper 真调 mapper）；
 * 变更入参非法（severity 非法/内容非法/alarmType 缺失）抛 IllegalArgumentException 不落库。
 *
 * <p>alarmType 治理（前端 ruoyi 化契约）：重复标识 400「报警标识已存在」；预置规则
 * （库值 settingContent configurable!=true）标识禁改 400「系统预置报警标识不可修改」；
 * list 行追加 deviceLabels（槽中文 StationParamMeta 同源 + attr 中文 registry attrDefs 同源，
 * mock registry 桩验证解析与回退）。</p>
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
        // 空 registry 桩：label 全走回退原文（具体解析用例在 list_appendsDeviceLabels 注入 mock registry）
        controller = new AsmAlarmRuleController(ruleMapper, index,
                new AsmDeviceLabelService(uid -> null));
    }

    /** settingContent 无 configurable 字段（parse→false=系统预置，对应 seed 规则 3/14/15/17/18/22 形态）。 */
    private static AsmAlarmRule validRule(String severity) {
        return AsmAlarmRule.builder()
                .alarmType("3").severity(severity)
                .settingContent("{\"name\":\"漏水\",\"enabled\":true,"
                        + "\"device_info\":{\"logicdevice_station.security_alarm\":[\"water_leak\"]}}")
                .sort(1).build();
    }

    /** settingContent 带 configurable:true（用户可配置行，端点创建/参数化 seed 规则形态）。 */
    private static AsmAlarmRule configurableRule(String severity) {
        return AsmAlarmRule.builder()
                .alarmType("3").severity(severity)
                .settingContent("{\"name\":\"温度\",\"enabled\":true,\"configurable\":true,"
                        + "\"device_info\":{\"logicdevice_station.th\":[\"temperature\"]}}")
                .sort(1).build();
    }

    @Test
    void add_persistsThenReloadsIndex() {
        lenient().when(ruleMapper.selectAll()).thenReturn(Collections.<AsmAlarmRule>emptyList());
        when(ruleMapper.insert(any(AsmAlarmRule.class))).thenReturn(1);
        controller.add(validRule("0"));
        verify(ruleMapper).insert(any(AsmAlarmRule.class));
        verify(index).reload();
    }

    @Test
    void edit_persistsThenReloadsIndex() {
        AsmAlarmRule stored = validRule("2");
        stored.setId(9L); // edit 契约要求带 id，否则更新无落点
        when(ruleMapper.selectById(9L)).thenReturn(stored);
        lenient().when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(stored));
        when(ruleMapper.update(any(AsmAlarmRule.class))).thenReturn(1);
        AsmAlarmRule rule = validRule("2");
        rule.setId(9L);
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
        lenient().when(ruleMapper.insert(any(AsmAlarmRule.class))).thenReturn(1);
        AsmAlarmRule bad = validRule("9");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.add(bad));
        verify(ruleMapper, never()).insert(any(AsmAlarmRule.class));
        verify(index, never()).reload();
    }

    @Test
    void add_badSettingContentRejected() {
        AsmAlarmRule bad = AsmAlarmRule.builder().alarmType("3").severity("0")
                .settingContent("not-json").build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.add(bad));
        verify(ruleMapper, never()).insert(any(AsmAlarmRule.class));
    }

    @Test
    void add_missingAlarmTypeRejected() {
        AsmAlarmRule bad = validRule("0");
        bad.setAlarmType(null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.add(bad));
        verify(ruleMapper, never()).insert(any(AsmAlarmRule.class));
    }

    @Test
    void add_duplicateAlarmTypeRejected400_noPersist() {
        AsmAlarmRule existing = validRule("0");
        existing.setId(1L);
        lenient().when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(existing));
        lenient().when(ruleMapper.insert(any(AsmAlarmRule.class))).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.add(validRule("0")));

        assertEquals(Integer.valueOf(400), ex.getCode());
        assertEquals("报警标识已存在: 3", ex.getMessage());
        verify(ruleMapper, never()).insert(any(AsmAlarmRule.class));
        verify(index, never()).reload();
    }

    @Test
    void edit_presetAlarmTypeChangeRejected400_noUpdate() {
        // 库值无 configurable（系统预置）+ 请求改标识 → 拒绝；其余字段更新同批被拦
        AsmAlarmRule stored = validRule("0");
        stored.setId(9L);
        when(ruleMapper.selectById(9L)).thenReturn(stored);
        lenient().when(ruleMapper.update(any(AsmAlarmRule.class))).thenReturn(1);

        AsmAlarmRule request = validRule("0");
        request.setId(9L);
        request.setAlarmType("999");

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.edit(request));

        assertEquals(Integer.valueOf(400), ex.getCode());
        assertEquals("系统预置报警标识不可修改", ex.getMessage());
        verify(ruleMapper, never()).update(any(AsmAlarmRule.class));
        verify(index, never()).reload();
    }

    @Test
    void edit_configurableRuleAlarmTypeChangeAllowed() {
        // configurable=true（用户可配置）行允许改标识（仍受唯一校验约束；本例仅自身一行无冲突）
        AsmAlarmRule stored = configurableRule("0");
        stored.setId(9L);
        when(ruleMapper.selectById(9L)).thenReturn(stored);
        lenient().when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(stored));
        when(ruleMapper.update(any(AsmAlarmRule.class))).thenReturn(1);

        AsmAlarmRule request = configurableRule("0");
        request.setId(9L);
        request.setAlarmType("888");

        controller.edit(request);
        verify(ruleMapper).update(any(AsmAlarmRule.class));
        verify(index).reload();
    }

    @Test
    void edit_missingRuleIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.edit(validRule("0")));
        verify(ruleMapper, never()).update(any(AsmAlarmRule.class));
    }

    @Test
    void edit_unknownRuleIdRejected() {
        when(ruleMapper.selectById(404L)).thenReturn(null);
        AsmAlarmRule rule = validRule("0");
        rule.setId(404L);
        assertThrows(IllegalArgumentException.class, () -> controller.edit(rule));
        verify(ruleMapper, never()).update(any(AsmAlarmRule.class));
    }

    @Test
    void list_appendsDeviceLabels_withRegistryResolutionAndFallback() {
        // device_info 三槽顺序：th（registry 有 def + 缺 def 混合）/ standard_gas.co（多实例槽中文名，
        // registry 无设备）/ regulated_power（非槽 uid）——一次锁死槽解析、attr 解析、双回退与顺序契约
        AsmAlarmRule rule = AsmAlarmRule.builder().alarmType("1").severity("0").sort(1)
                .settingContent("{\"name\":\"温度\",\"enabled\":true,\"device_info\":{"
                        + "\"logicdevice_station.th\":[\"temperature\",\"temperature_indoor\"],"
                        + "\"logicdevice_station.standard_gas.co\":[\"gas_pressure_remaining\"],"
                        + "\"logicdevice_station.regulated_power\":[\"voltage\"]}}")
                .build();
        when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(rule));

        LogicDevice th = mock(LogicDevice.class, withSettings().lenient());
        LogicAttributeDefine tempDef = new LogicAttributeDefine();
        tempDef.setAttrId("temperature");
        tempDef.setDisplayName("温度");
        when(th.getAttrDefs()).thenReturn(Collections.singletonList(tempDef));
        Map<String, LogicDevice> registry = new HashMap<>();
        registry.put("logicdevice_station.th", th);
        AsmAlarmRuleController controllerWithRegistry = new AsmAlarmRuleController(ruleMapper, index,
                new AsmDeviceLabelService(registry::get));

        List<AsmAlarmRuleRowDto> rows = controllerWithRegistry.list();

        assertEquals(1, rows.size());
        assertSame(rule, rows.get(0).getRule());
        List<AsmDeviceLabelsDto> labels = rows.get(0).getDeviceLabels();
        assertEquals(3, labels.size());
        // 槽中文（单实例）+ registry displayName 解析 + 缺 def attr 如实回退 attrId
        assertEquals("站房温湿度监测仪", labels.get(0).getSlot());
        assertEquals(Arrays.asList("温度", "temperature_indoor"), labels.get(0).getAttrs());
        // 多实例槽各带实例中文名；registry 无该设备 → attr 回退 attrId 原文
        assertEquals("CO标气", labels.get(1).getSlot());
        assertEquals(Collections.singletonList("gas_pressure_remaining"), labels.get(1).getAttrs());
        // 非槽 uid（seed 串错/未知槽）如实回退 uid 原文，不猜
        assertEquals("logicdevice_station.regulated_power", labels.get(2).getSlot());
        assertEquals(Collections.singletonList("voltage"), labels.get(2).getAttrs());
    }

    @Test
    void list_delegatesToMapper() {
        when(ruleMapper.selectAll()).thenReturn(Collections.singletonList(validRule("0")));
        List<AsmAlarmRuleRowDto> rows = controller.list();
        assertEquals(1, rows.size());
        assertEquals("3", rows.get(0).getRule().getAlarmType());
    }

    @Test
    void getById_delegatesToMapper() {
        when(ruleMapper.selectById(7L)).thenReturn(validRule("0"));
        assertEquals("3", controller.getById(7L).getAlarmType());
    }
}
