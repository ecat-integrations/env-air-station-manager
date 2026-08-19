package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * ASM 统计聚合区间模式——桶标注与 WHERE 开闭的单一真相源（机制同 ADM AdmIntervalMode，ASM 自建不 import ADM）。
 *
 * <p>应用场景：三级 stat 物化的桶标注与窗口开闭由本枚举决定——{@link #BACK} 桶标注=右沿
 * （{@code bucket(t-1µs)+gap}）、WHERE {@code data_time > ? AND <= ?}（后闭，整点采样归当前窗右沿，
 * 边界无歧义）；{@link #FRONT} 桶标注=左沿、WHERE {@code data_time >= ? AND < ?}（前闭区间语义）。</p>
 *
 * <p>持久化：{@link #code()} 落 stat 表 {@code interval_mode} smallint 列（进 PK，多 mode 并存）；
 * {@link #fromCode(int)} 解码读路径，非法码抛不臆造默认。字面量 {@code 1}/{@code 2} 只许出现在本枚举定义处。</p>
 *
 * @author coffee
 */
public enum AsmIntervalMode {
    /** 前闭区间 [S,E)：桶标注=左沿，WHERE data_time &gt;= ? AND &lt; ?。 */
    FRONT(1),
    /** 后闭区间 (L,R]：桶标注=右沿，WHERE data_time &gt; ? AND &lt;= ?；整点采样归当前窗右沿。 */
    BACK(2);

    private final int code;

    AsmIntervalMode(int code) {
        this.code = code;
    }

    /** 落库码（stat.interval_mode smallint，进 PK）。 */
    public int code() {
        return code;
    }

    /** 读库解码（stat 行 interval_mode → 枚举）；非法码抛 IllegalArgumentException，防脏数据臆造默认。 */
    public static AsmIntervalMode fromCode(int code) {
        for (AsmIntervalMode m : values()) {
            if (m.code == code) {
                return m;
            }
        }
        throw new IllegalArgumentException("非法 interval_mode 码: " + code + "（合法: 1=FRONT, 2=BACK）");
    }

    /** 配置解析（asm_config_stat / compute_log 的字符串值 → 枚举）；null/未知名严格抛。 */
    public static AsmIntervalMode of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("interval_mode 配置值为 null（合法: FRONT/BACK）");
        }
        for (AsmIntervalMode m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        throw new IllegalArgumentException("非法 interval_mode 配置值: " + name + "（合法: FRONT/BACK）");
    }
}
