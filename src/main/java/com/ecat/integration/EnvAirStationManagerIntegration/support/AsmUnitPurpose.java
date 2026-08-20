package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * ASM 单位偏好用途——{@code asm_config_unit.purpose} 列枚举（D4，同 adm_config_unit 语义）。
 *
 * <p>STORAGE=物化存储单位 / STANDARD=标准展示 / MONITOR=卡片自定义展示 / HISTORY=历史自定义展示。
 * 每 (series, purpose) 一行窄行；读出口缺行显原生（native 保底是设计语义非兜底）。
 * STANDARD/MONITOR 对齐 ADM 双模式：standard 模式读 STANDARD 行（seed 默认=attr nativeUnit，与 STORAGE
 * 同源）、custom 模式读 MONITOR 行；配置端点只开放写 MONITOR/HISTORY（同 ADM，STANDARD 行是 seed
 * 管理的标准口径不开放用户改）。</p>
 *
 * @author coffee
 */
public enum AsmUnitPurpose {
    /** 物化存储单位（stat 桶单位换算目标）。 */
    STORAGE,
    /** 标准展示单位（snapshot/SSE standard 模式读出口；seed 落默认=attr nativeUnit）。 */
    STANDARD,
    /** 监控首页卡片自定义展示单位。 */
    MONITOR,
    /** 历史查询自定义展示单位。 */
    HISTORY;

    /** 配置/DDL 值解析；null/未知名严格抛。 */
    public static AsmUnitPurpose of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("purpose 配置值为 null（合法: STORAGE/STANDARD/MONITOR/HISTORY）");
        }
        for (AsmUnitPurpose p : values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("非法 purpose 配置值: " + name + "（合法: STORAGE/STANDARD/MONITOR/HISTORY）");
    }
}
