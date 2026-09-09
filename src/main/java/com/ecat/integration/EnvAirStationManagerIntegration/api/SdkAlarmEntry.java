package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * SDK 报警条目行——不可变 DTO（api 包零依赖契约），行形状与前端报警表格（AsmAlarmList.vue）列一致
 * 外加 alarmType 查询键回显。
 *
 * <p>机对机口径（{@code AirStationSdk#queryAlarmEntries} 返回行）：标识+展示名双字段
 * （模式同 {@link SdkParamMeta}），原始值不加工（模式同 {@link SdkStatRow}）——deviceLabel/attrLabel
 * 解析不到为 null（回退 uid/attrId 留给消费方，不内联回退）；description 原文透传（串内 uid/attrId
 * 的中文重组是前端展示逻辑）；durationMs 持续中为 null 不虚构时长。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkAlarmEntry {

    /** 报警标识（查询键回显，消费方对账用；语义见 listAlarmTypes 目录）。 */
    String alarmType;
    /** 规则名（setting_content.name 快照）。 */
    String ruleName;
    /** 站房逻辑设备 uniqueId（标识）。 */
    String logicDeviceUniqueId;
    /** 槽中文名；解析不到=null（消费方回退 logicDeviceUniqueId）。 */
    String deviceLabel;
    /** 触发参数 id（标识）。 */
    String attrId;
    /** attr 中文 displayName；解析不到=null（消费方回退 attrId）。 */
    String attrLabel;
    /** 级别原始码 '0'普通/'1'重要/'2'紧急（映射 tag 留给消费方）。 */
    String severity;
    /** 生命周期态：ACTIVE（持续中，recoverTime/durationMs=null）/ INACTIVE（已闭单）。 */
    String status;
    /** 触发时刻（=库列 start_time，episode 首触发）。 */
    Instant triggerTime;
    /** 恢复时刻（=库列 end_time，闭单时刻）；持续中=null。 */
    Instant recoverTime;
    /** 持续时长毫秒（=recoverTime−triggerTime）；持续中=null（不虚构时长）。 */
    Long durationMs;
    /** 人读描述原文（串内 uid/attrId 由消费方按 label 双字段自行重组）。 */
    String description;
}
