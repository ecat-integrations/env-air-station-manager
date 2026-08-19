package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * SDK 动态参数清单条目——一个可查询 stat 参数的元数据投影（{@code AirStationSdk#listStatParams()} 返回行）。
 *
 * <p>不可变 DTO（对外稳定契约包成员）。消费方据此在 ConfigFlow 动态罗列上报因子，
 * ASM 加参数后零改动自动跟随。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkParamMeta {

    /** 站房逻辑设备 uniqueId。 */
    String logicDeviceUniqueId;

    /** logic attr id。 */
    String attrId;

    /** 参数中文显示名（live attr getDisplayName）；设备未绑定/attr 缺席降级为 attrId 原文（读路径容错）。 */
    String paramDisplayName;

    /** stat 存储单位（full key 形式）；null=无量纲。 */
    String storageUnit;

    /** 适用粒度掩码：bit0=分 / bit1=5分 / bit2=时（ASM 无 day 级）。 */
    int applicableGranularityMask;
}
