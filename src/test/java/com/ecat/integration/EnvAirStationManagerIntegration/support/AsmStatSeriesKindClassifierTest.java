package com.ecat.integration.EnvAirStationManagerIntegration.support;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.NumberAttribute;
import com.ecat.core.State.TextAttribute;
import com.ecat.core.State.Unit.TemperatureUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类器白名单单测——15 key 逐一断言锁拼写（attrId 字面量不 import mapping 仓常量，拼写错误
 * 只能靠这里拦）+ 未登记 id=NONE + null 严格抛 + seed 准入判据（numeric or 白名单）。
 *
 * @author coffee
 */
class AsmStatSeriesKindClassifierTest {

    /** 可实例化的最小 NumberAttribute（isSeedEligible 的 numeric 分支桩）。 */
    static class TestNumAttr extends NumberAttribute<Double> {
        TestNumAttr(String attrId) {
            super(attrId, AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 1, true, true);
        }

        @Override
        protected Double convertToType(double value) {
            return value;
        }
    }

    /** 站房非数值 attr 桩（TextAttribute 是 core 具体类可直接实例化）。 */
    private static TextAttribute textAttr(String attrId) {
        return new TextAttribute(attrId, AttributeClass.VALUE, null, null, false);
    }

    @Test
    void alarmWhitelistEightKeys() {
        // SecurityAlarm 6 + Camera 2 + ElectronicFence 1 = 9 项，intrusion_alarm 两设备共用 → 8 key
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("ir_alarm"));
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("water_leak"));
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("temp_alarm_1"));
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("temp_alarm_2"));
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("smoke_1"));
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("smoke_2"));
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("intrusion_alarm"));
        assertEquals(AsmStatSeriesKind.ALARM, AsmStatSeriesKindClassifier.kindOf("interference_alarm"));
    }

    @Test
    void stateWhitelistSevenKeys() {
        // 空调 3、照明/排风/门禁锁/校准器气路各 1
        assertEquals(AsmStatSeriesKind.STATE, AsmStatSeriesKindClassifier.kindOf("running_mode"));
        assertEquals(AsmStatSeriesKind.STATE, AsmStatSeriesKindClassifier.kindOf("fan_speed"));
        assertEquals(AsmStatSeriesKind.STATE, AsmStatSeriesKindClassifier.kindOf("power_status"));
        assertEquals(AsmStatSeriesKind.STATE, AsmStatSeriesKindClassifier.kindOf("switch_status"));
        assertEquals(AsmStatSeriesKind.STATE, AsmStatSeriesKindClassifier.kindOf("speed"));
        assertEquals(AsmStatSeriesKind.STATE, AsmStatSeriesKindClassifier.kindOf("lock_status"));
        assertEquals(AsmStatSeriesKind.STATE, AsmStatSeriesKindClassifier.kindOf("current_gas_type"));
    }

    @Test
    void unregisteredIdsAreNone() {
        // 计算属性（阈值派生，mapable=false）——注意 temp_alarm_1/2 是 bind attr 在白名单，
        // 而 temp_alarm 是 TH 计算属性，一字之差必须 NONE
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("temp_alarm"));
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("pm25_alarm"));
        // 共享状态（bus 聚合/派生）
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("alarm_status"));
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("running_status"));
        // 用户裁定剔除的设备控制属性 / 事件快照
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("ai_running"));
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("fence_status"));
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("relay_status"));
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("last_person"));
        // 数值 bind attr 不在白名单（numeric 自动走 AVG seed，无须登记）
        assertEquals(AsmStatSeriesKind.NONE, AsmStatSeriesKindClassifier.kindOf("temperature"));
    }

    @Test
    void nullAttrIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> AsmStatSeriesKindClassifier.kindOf(null));
    }

    @Test
    void seedEligible_numericAttrRegardlessOfWhitelist() {
        // numeric 恒可 seed（AVG）；即使 id 未登记（temperature）也 true
        assertTrue(AsmStatSeriesKindClassifier.isSeedEligible(new TestNumAttr("temperature")));
        assertTrue(AsmStatSeriesKindClassifier.isSeedEligible(new TestNumAttr("ir_alarm")));
    }

    @Test
    void seedEligible_whitelistedTextAttrPassesOthersDont() {
        assertTrue(AsmStatSeriesKindClassifier.isSeedEligible(textAttr("water_leak")), "ALARM 白名单文本可 seed");
        assertTrue(AsmStatSeriesKindClassifier.isSeedEligible(textAttr("lock_status")), "STATE 白名单文本可 seed");
        assertFalse(AsmStatSeriesKindClassifier.isSeedEligible(textAttr("status_text")), "白名单外文本不 seed");
        assertFalse(AsmStatSeriesKindClassifier.isSeedEligible(textAttr("last_person")), "事件快照 bind attr 不 seed");
    }

    @Test
    void seedEligible_nullAttrReturnsFalse() {
        // consumer 反查 attrs 查无（在途事件 vs attr 卸载竞态）——合法边界不 seed，
        // 与原 instanceof 对 null 恒 false 行为等价
        assertFalse(AsmStatSeriesKindClassifier.isSeedEligible(null));
    }
}
