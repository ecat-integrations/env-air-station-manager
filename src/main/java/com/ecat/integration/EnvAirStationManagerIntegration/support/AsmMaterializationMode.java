package com.ecat.integration.EnvAirStationManagerIntegration.support;

import java.util.EnumSet;

/**
 * ASM 物化范围模式——{@code asm_config_stat.materialization_mode}（FRONT/BACK/BOTH，默认 BOTH）的
 * 单一真相源。
 *
 * <p>应用场景：调度器/物化引擎按 {@link #modes()} 遍历区间模式各跑一趟三级级联。BOTH=两标都算
 * （默认——前端切 view 永远有数据）；切换 scope 不删已物化的另一 mode 桶（历史不可变），也不回溯历史。</p>
 *
 * @author coffee
 */
public enum AsmMaterializationMode {
    /** 只算前标 [S,E)。 */
    FRONT,
    /** 只算后标 (L,R]。 */
    BACK,
    /** 两标都算（seed 默认）。 */
    BOTH;

    /** 该 scope 下要物化的区间模式集合（engine 遍历入口）。 */
    public EnumSet<AsmIntervalMode> modes() {
        if (this == FRONT) {
            return EnumSet.of(AsmIntervalMode.FRONT);
        }
        if (this == BACK) {
            return EnumSet.of(AsmIntervalMode.BACK);
        }
        return EnumSet.allOf(AsmIntervalMode.class);
    }

    /** 配置/DDL 值解析；null/未知名严格抛不臆造默认。 */
    public static AsmMaterializationMode of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("materialization_mode 配置值为 null（合法: FRONT/BACK/BOTH）");
        }
        for (AsmMaterializationMode m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        throw new IllegalArgumentException("非法 materialization_mode 配置值: " + name + "（合法: FRONT/BACK/BOTH）");
    }
}
