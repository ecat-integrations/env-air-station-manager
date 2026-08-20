package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import lombok.Value;

/**
 * ASM SSE 单事件载荷——总览页瓦片/抽屉的最小增量 patch 单元。
 *
 * <p>字段契约（总览页精修设计定案 2/3/4）：一帧 = 一个站房逻辑设备一个 attr 的新值。前端按
 * {@code (logicDeviceUniqueId, attrId)} 原地 patch 瓦片核心参数与抽屉行；{@code displayName} 中文名、
 * {@code unit} 单位<b>符号</b>（UnitInfo.getName，如 °C/V）由后端出口统一供给，前端不再维护映射。</p>
 *
 * <p>帧名 {@link #TYPE}（"device.data.update"）与 ADM 同名——前端具名 listener 匹配 {@code event:} 帧头。</p>
 */
@Value
public class AsmSseEvent {

    /** SSE 具名帧名（broadcast 必须 .name(TYPE)，否则前端 addEventListener 永不触发）。 */
    public static final String TYPE = "device.data.update";

    /** 站房逻辑设备 uniqueId（logicdevice_station.*，前端 patch 定位键）。 */
    String logicDeviceUniqueId;

    /** logic attr id。 */
    String attrId;

    /** 参数中文名（attr def displayName，缺省回退 attrId）。 */
    String displayName;

    /** 数值业务值——custom 出口（MONITOR 偏好换算后；非数值属性为 null）。 */
    Double displayValue;

    /**
     * 数值业务值——standard 出口（STANDARD 行换算后；非数值属性为 null）。
     * 双值同推（同 ADM 监控页方案）：前端按当前 unit 模式选 standardValue/standardUnit 或 displayValue/unit，
     * 切换即时生效，帧内零换算。
     */
    Double standardValue;

    /** 单位符号——standard 出口（STANDARD 行单位符号化；null=无量纲）。 */
    String standardUnit;

    /** 非数值展示串（开关/文本态属性；数值属性为 null）。 */
    String valueText;

    /** 单位符号（UnitInfo.getName，如 °C、V、ppm；null=无量纲）。 */
    String unit;

    /** 该值时刻（live state.lastUpdated 的 ISO-8601 串；字符串化避免裸 ObjectMapper 无 JavaTimeModule 序列化失败）。 */
    String updateTime;

    /** 主状态中文名（AttributeStatus.getDescription；可能 null=无状态）。 */
    String statusName;

    /** 状态枚举 key（AttributeStatus.name()，如 NORMAL/ALARM；前端按枚举 key 着色，不按中文串）。 */
    String status;

    /**
     * 规则引擎该设备当前有活跃 episode（registry 内存重算，零 SQL）。仅维护卡片徽章的 episode 侧，
     * <b>不是</b>设备级报警布尔（attr 侧报警态随本帧 status 字段更新，前端对全 attr statuses 求并集——
     * ADM 同构，杜绝单侧覆另一侧）。滞后边界：sweep 摘槽后的消退靠下一帧或进页面 snapshot 对齐。
     */
    boolean ruleAlarmActive;
}
