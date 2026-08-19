package com.ecat.integration.EnvAirStationManagerIntegration.rule;

/**
 * 动环报警三类判定模式（+断电恢复特化）——语义移植自 env-alarm-manager EnvAlarmEventHandler：
 *
 * <ul>
 *   <li>{@link #RANGE_DURATION} range+持续时间：超限起算、恢复清零、持续满 N 分钟触发
 *       （原 1 温度/2 湿度/4 供电/5 稳压/6 电流/12 采样管温度/1010 超范围）；</li>
 *   <li>{@link #INSTANT_THRESHOLD} 瞬时阈值：单值越限即触发（原 23 洁净度/9 标气更换）；</li>
 *   <li>{@link #STATUS_MATCH} 状态串：displayValue 等于/包含配置串触发
 *       （原 3 漏水/14 设备报警/17 异常进入/18 干扰/22 门禁）；</li>
 *   <li>{@link #POWER} 断电+恢复：低于阈值报警、恢复后 force 记恢复（原 15）。</li>
 * </ul>
 *
 * @author coffee
 */
public enum AsmAlarmCheckType {

    RANGE_DURATION,
    INSTANT_THRESHOLD,
    STATUS_MATCH,
    POWER;

    /** 解析 check 字段；未知值抛（坏配置由索引层隔离）。 */
    public static AsmAlarmCheckType of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("check 类型为空");
        }
        for (AsmAlarmCheckType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 check 类型: " + name);
    }
}
