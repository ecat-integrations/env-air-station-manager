package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttributeClass;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽屉参数排序（中文拼音序）+ snapshot unit 模式解析（standard|custom）纯函数单测。
 *
 * <p>排序键 = displayName（中文 Collator，null 回退 attrId 字典序）；unit 模式对齐 ADM：
 * custom→MONITOR 偏好 / 其余（standard/缺省/旧值）→STANDARD 行。禁 sleep 全同步。</p>
 *
 * @author coffee
 */
class AsmSnapshotOrderAndUnitModeTest {

    private static com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto row(
            String attrId, String displayName) {
        return com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto.builder()
                .attrId(attrId).displayName(displayName).build();
    }

    @Test
    void attrOrder_sortsChineseByPinyin() {
        // 拼音序 bao jing(报警) < yun xing(运行) < zai xian(在线)，与 attrId 字典序（alarm<online<running）方向不同，按拼音判
        assertTrue(AsmSnapshotService.ATTR_ORDER.compare(row("running", "运行状态"), row("alarm", "报警状态")) > 0);
        assertTrue(AsmSnapshotService.ATTR_ORDER.compare(row("alarm", "报警状态"), row("online", "在线状态")) < 0);
        assertTrue(AsmSnapshotService.ATTR_ORDER.compare(row("running", "运行状态"), row("online", "在线状态")) < 0,
                "yun xing 应排在 zai xian 前（拼音序，非 attrId 字典序 running<online）");
    }

    @Test
    void attrOrder_nullDisplayName_fallsBackToAttrId() {
        // def 缺席 displayName null → 回退 attrId 字典序（Collator 对 ASCII 与字典序一致）
        assertTrue(AsmSnapshotService.ATTR_ORDER.compare(row("b", null), row("a", null)) > 0);
        // null displayName 与有名行混排：null 按其 attrId 参与
        assertTrue(AsmSnapshotService.ATTR_ORDER.compare(row("a", null), row("z", "运行状态")) < 0,
                "a 的拼音序按 attrId 'a' 排在中文前");
    }

    @Test
    void attrOrder_resetCommandSortsFirstInGroup_polyphoneChong() {
        // 「重置*」多音字例外（用户定案）：Collator 默认按 zhòng 排会把「重置命令」甩到 z 段尾，按 chóng 应组内最前
        assertTrue(AsmSnapshotService.ATTR_ORDER.compare(row("reset_command", "重置命令"), row("cal_command", "标定命令")) < 0,
                "重置（chóng）应排在标定（biao）前");
        assertTrue(AsmSnapshotService.ATTR_ORDER.compare(row("reset_command", "重置命令"), row("auto_command", "自动命令")) < 0,
                "重置（chóng）应排在自动（zi）前");
    }

    @Test
    void sortAttrRows_groupsStatusThenCommandThenNumeric_pinyinWithinGroup() {
        Map<String, LogicAttributeDefine> defs = new HashMap<>();
        defs.put("used_spots", new LogicAttributeDefine("used_spots", AttributeClass.NUMERIC, null, null, 0, false, Double.class));
        defs.put("alarm_status", new LogicAttributeDefine("alarm_status", AttributeClass.ALARM_STATUS, null, null, 0, false, String.class));
        defs.put("cal_command", new LogicAttributeDefine("cal_command", AttributeClass.TEXT, null, null, 0, false, String.class));
        defs.put("reset_command", new LogicAttributeDefine("reset_command", AttributeClass.TEXT, null, null, 0, false, String.class));
        List<AsmSnapshotAttrDto> rows = Arrays.asList(
                row("used_spots", "剩余斑点数"),
                row("reset_command", "重置记录"),
                row("alarm_status", "报警状态"),
                row("cal_command", "标定命令"));
        AsmSnapshotService.sortAttrRows(rows, defs);
        assertEquals(Arrays.asList("报警状态", "重置记录", "标定命令", "剩余斑点数"),
                Arrays.asList(rows.get(0).getDisplayName(), rows.get(1).getDisplayName(),
                        rows.get(2).getDisplayName(), rows.get(3).getDisplayName()),
                "分组序：状态类(报警)→命令类(重置chóng在前)→数值类(剩余斑点)；DEF 占位行随 def 分组");
    }

    @Test
    void sortAttrRows_defPlaceholderFollowsDefGroup_notDumpedAtTail() {
        Map<String, LogicAttributeDefine> defs = new HashMap<>();
        defs.put("ai_running", new LogicAttributeDefine("ai_running", AttributeClass.TEXT, null, null, 0, false, String.class));
        // 数值 def 占位行（无值）：经 def 归数值组，不落状态组也不单独垫底
        defs.put("used_spots", new LogicAttributeDefine("used_spots", AttributeClass.NUMERIC, null, null, 0, false, Double.class));
        List<AsmSnapshotAttrDto> rows = Arrays.asList(
                row("used_spots", "剩余斑点数"),
                row("ai_running", "AI运行状态"));
        AsmSnapshotService.sortAttrRows(rows, defs);
        assertEquals("AI运行状态", rows.get(0).getDisplayName(), "TEXT def 行归状态组在前，无值数值 DEF 行归数值组在后");
        assertEquals("剩余斑点数", rows.get(1).getDisplayName());
    }

    @Test
    void purposeForUnit_customAppliesMonitorPref() {
        assertEquals(AsmUnitPurpose.MONITOR, AsmSnapshotService.purposeForUnit("custom"));
    }

    @Test
    void purposeForUnit_standardAndDefaultAndLegacyUseStandardRow() {
        // 对齐 ADM：非 custom 的任意值（standard / 缺省 / 旧值）一律按 standard（STANDARD 行）处理
        assertEquals(AsmUnitPurpose.STANDARD, AsmSnapshotService.purposeForUnit("standard"));
        assertEquals(AsmUnitPurpose.STANDARD, AsmSnapshotService.purposeForUnit(null));
        assertEquals(AsmUnitPurpose.STANDARD, AsmSnapshotService.purposeForUnit("instrument"));
    }

    @Test
    void purposeForUnit_blankIsIllegal() {
        assertThrows(IllegalArgumentException.class, () -> AsmSnapshotService.purposeForUnit(" "));
        assertThrows(IllegalArgumentException.class, () -> AsmSnapshotService.purposeForUnit(""));
    }
}
