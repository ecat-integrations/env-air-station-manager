package com.ecat.integration.EnvAirStationManagerIntegration.support;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.NumberAttribute;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 统计 series 分类器——attrId → {@link AsmStatSeriesKind} 白名单（非数值统计准入的唯一真相源）。
 *
 * <p><b>为什么按 attrId 字符串字面量建白名单</b>：15 个 id 跨设备类型无归类冲突
 * （intrusion_alarm 被 Camera/ElectronicFence 共用，同属 ALARM，15 key 覆盖 16 项统计对象），
 * 纯 id 判定无须设备上下文；字面量不 import logicdevice-airstation 常量类——保持 ASM 对
 * mapping 仓零运行时依赖，拼写由单测逐 key 锁死。新增非数值统计参数 = 本类加一行 +
 * 分类文档明细表同步（两处缺一不可，以本白名单为准）。</p>
 *
 * <p><b>未登记的 attrId 一律 NONE</b>：计算属性（mapable=false，如 temp_alarm 由阈值派生）、
 * 共享状态（alarm_status 等 bus 聚合）、命令/事件快照/用户裁定剔除的控制属性——默认不统计，
 * 新参数无须显式排除。</p>
 *
 * @author coffee
 */
public final class AsmStatSeriesKindClassifier {

    /** 白名单：attrId → 类别（2026-09-08 用户裁定放开范围）。 */
    private static final Map<String, AsmStatSeriesKind> WHITELIST;

    static {
        Map<String, AsmStatSeriesKind> m = new HashMap<>();
        // ALARM 8 key 覆盖 9 项（intrusion_alarm 两设备共用）：SecurityAlarm 红外/漏水/温感×2/烟感×2 +
        // Camera 入侵/干扰 + ElectronicFence 入侵
        m.put("ir_alarm", AsmStatSeriesKind.ALARM);
        m.put("water_leak", AsmStatSeriesKind.ALARM);
        m.put("temp_alarm_1", AsmStatSeriesKind.ALARM);
        m.put("temp_alarm_2", AsmStatSeriesKind.ALARM);
        m.put("smoke_1", AsmStatSeriesKind.ALARM);
        m.put("smoke_2", AsmStatSeriesKind.ALARM);
        m.put("intrusion_alarm", AsmStatSeriesKind.ALARM);
        m.put("interference_alarm", AsmStatSeriesKind.ALARM);
        // STATE 7 key：空调模式/风速/电源、照明开关、排风扇档位、门禁锁、校准器气路
        m.put("running_mode", AsmStatSeriesKind.STATE);
        m.put("fan_speed", AsmStatSeriesKind.STATE);
        m.put("power_status", AsmStatSeriesKind.STATE);
        m.put("switch_status", AsmStatSeriesKind.STATE);
        m.put("speed", AsmStatSeriesKind.STATE);
        m.put("lock_status", AsmStatSeriesKind.STATE);
        m.put("current_gas_type", AsmStatSeriesKind.STATE);
        WHITELIST = Collections.unmodifiableMap(m);
    }

    private AsmStatSeriesKindClassifier() {
    }

    /**
     * attrId → 统计类别。
     *
     * @param attrId logic attr id（非 null）
     * @return 白名单命中类别；未登记一律 {@link AsmStatSeriesKind#NONE}
     * @throws IllegalArgumentException attrId 为 null（调用方契约破坏，严格模式显式暴露）
     */
    public static AsmStatSeriesKind kindOf(String attrId) {
        if (attrId == null) {
            throw new IllegalArgumentException("kindOf attrId 不可为 null");
        }
        AsmStatSeriesKind kind = WHITELIST.get(attrId);
        return kind != null ? kind : AsmStatSeriesKind.NONE;
    }

    /**
     * series 是否可 seed 进统计——数值（AVG）或白名单非数值（ALARM/STATE）。
     *
     * <p>seed 两路（启动枚举 AsmSeedService.seedStartup / consumer 首见 AsmDataSampleConsumer
     * 共用本判据：判据抽到分类器同源，防双路行为漂移。attr 为 null 返 false——consumer 反查
     * 设备 attrs 可能查无（在途事件 vs attr 卸载竞态，合法边界不 seed），与原先
     * {@code instanceof} 对 null 恒 false 的行为等价。</p>
     */
    public static boolean isSeedEligible(AttributeBase<?> attr) {
        if (attr == null) {
            return false;
        }
        return attr instanceof NumberAttribute || kindOf(attr.getAttributeID()) != AsmStatSeriesKind.NONE;
    }
}
