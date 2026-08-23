package com.ecat.integration.EnvAirStationManagerIntegration.driver;

import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationProvisionProfile;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * provision 阶段记的业务上下文，由 FlowDriver 用 flowId→ctx Map 维护。
 *
 * <p>不塞 ConfigFlowService/FlowContext（会污染物理设备 entry.data）；仿 ImportFlowTestDriver driver 侧自持。
 * 含：参数类型、操作(ADD/REPLACE)、旧设备 deviceId（replace 时，原子收口按引用判断处理）、profile、
 * reusedEntryId（自动复用已存在设备时记其 entryId，tryBindExistingDevice 设 / submitFlowStep 读）。
 *
 * @author coffee
 */
@Getter
@RequiredArgsConstructor
public final class FlowContext {
    private final StationParamMeta stationParam;
    private final Operation operation;
    private final String oldDeviceId;
    private final StationProvisionProfile profile;
    /** tryBindExistingDevice 自动复用已存在设备时记其 entryId，供 submitFlowStep 读后返回前端；未复用时为 null。 */
    @Setter
    private String reusedEntryId;
}
