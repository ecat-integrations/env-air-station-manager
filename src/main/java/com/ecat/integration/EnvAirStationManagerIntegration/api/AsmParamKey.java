package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * SDK 查询参数键——站房逻辑设备 uniqueId + attrId 二元组（batch 查询最小寻址单元）。
 *
 * <p>不可变 DTO（对外稳定契约包成员，零 ruoyi/Spring/core 依赖）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class AsmParamKey {

    /** 站房逻辑设备 uniqueId（logicdevice_station.*）。 */
    String logicDeviceUniqueId;

    /** logic attr id。 */
    String attrId;
}
