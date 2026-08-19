package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * ASM 单位偏好用途——{@code asm_config_unit.purpose} 列枚举（D4，同 adm_config_unit 语义）。
 *
 * <p>STORAGE=物化存储单位 / MONITOR=卡片展示 / HISTORY=历史展示。每 (series, purpose) 一行窄行；
 * 读出口缺行显原生（native 保底是设计语义非兜底）。</p>
 *
 * @author coffee
 */
public enum AsmUnitPurpose {
    /** 物化存储单位（stat 桶单位换算目标）。 */
    STORAGE,
    /** 监控首页卡片展示单位。 */
    MONITOR,
    /** 历史查询展示单位。 */
    HISTORY;

    /** 配置/DDL 值解析；null/未知名严格抛。 */
    public static AsmUnitPurpose of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("purpose 配置值为 null（合法: STORAGE/MONITOR/HISTORY）");
        }
        for (AsmUnitPurpose p : values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("非法 purpose 配置值: " + name + "（合法: STORAGE/MONITOR/HISTORY）");
    }
}
