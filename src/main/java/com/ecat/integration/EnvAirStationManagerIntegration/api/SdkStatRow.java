package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * SDK stat 查询结果行——一个参数（站房设备 + attr）在一个时间桶上的 avg-only 聚合值投影。
 *
 * <p>不可变 DTO（对外稳定契约包成员）。value 是 STORAGE 存储单位的桶均值原值（机对机口径，
 * 不做展示偏好换算；消费方须按 {@link #getUnit()} 解释 value）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkStatRow {

    /** 站房逻辑设备 uniqueId。 */
    String logicDeviceUniqueId;

    /** logic attr id。 */
    String attrId;

    /** 桶标注时刻（UTC；BACK=右沿 / FRONT=左沿，由查询入参 mode 决定）。 */
    Instant dataTime;

    /** 桶均值（桶行存在但 avg_value 为 null 时为 null）。 */
    Double value;

    /** 有效样本数。 */
    Long validCount;

    /** 非空值计数。 */
    Long totalCount;

    /** value 的单位——{@code UnitInfo.getFullUnitString()} 全 key 形式（asm_config_unit STORAGE 行）；null=无量纲。 */
    String unit;
}
