package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Value;

/**
 * 历史查询参数键——站房逻辑设备 uniqueId + attrId 二元组（查询寻址最小单元）。
 *
 * @author coffee
 */
@Value
public class AsmHistoryParamKey {

    /** 站房逻辑设备 uniqueId（logicdevice_station.*）。 */
    String logicDeviceUniqueId;

    /** logic attr id。 */
    String attrId;

    public static AsmHistoryParamKey of(String logicDeviceUniqueId, String attrId) {
        return new AsmHistoryParamKey(logicDeviceUniqueId, attrId);
    }
}
