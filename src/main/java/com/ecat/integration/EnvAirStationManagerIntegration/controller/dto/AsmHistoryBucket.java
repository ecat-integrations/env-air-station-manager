package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * stat 桶行读模型（历史查询/SDK 共用投影，avg-only：无 statuses/validity 扩展列）。
 *
 * <p>非 BaseEntity 读模型——mapper XML 用 FQCN（DynamicJarLoader 只注册 domain/+BaseEntity 别名）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class AsmHistoryBucket {

    /** 桶标注（BACK=右沿 / FRONT=左沿，由查询 mode 决定）。 */
    Instant dataTime;

    /** 站房逻辑设备 uniqueId。 */
    String logicDeviceUniqueId;

    /** logic attr id。 */
    String attrId;

    /** 桶内均值。 */
    Double avgValue;

    /** 有效样本数。 */
    Long validCount;

    /** 非空值计数。 */
    Long totalCount;
}
