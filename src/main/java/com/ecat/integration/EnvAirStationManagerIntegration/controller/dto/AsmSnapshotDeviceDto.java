package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * snapshot 单设备行——一个站房逻辑设备的全属性当前态。
 *
 * @author coffee
 */
@Value
@Builder
public class AsmSnapshotDeviceDto {

    /** 站房逻辑设备 uniqueId（logicdevice_station.*）。 */
    String logicDeviceUniqueId;

    /** 全属性当前态（live 优先，raw 回放兜）。 */
    List<AsmSnapshotAttrDto> attrs;
}
