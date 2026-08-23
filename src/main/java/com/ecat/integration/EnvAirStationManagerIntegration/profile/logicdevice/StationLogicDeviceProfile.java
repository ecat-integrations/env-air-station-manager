package com.ecat.integration.EnvAirStationManagerIntegration.profile.logicdevice;

import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationParamMeta;
import com.ecat.integration.logicdeviceairstation.AirstationIntegration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 站房 logic device 的 provision profile（仿 ADM LogicDeviceProfile）。
 *
 * <p>所有类型槽的 logic device 走同一 {@code StationDeviceConfigFlow}，仅 device_type /
 * device_instance 不同，故单类 + 每槽一实例（{@link StationLogicDeviceProfileRegistry}）。
 * FlowDriver 驱动步序：{@code select_type}(device_type[+device_instance]→设 uniqueId) →
 * {@code attribute_mapping}(mapping.{attrId}=phyId，driver 动态读 schema 填) →
 * {@code final_confirm}(confirmed=true→CREATE_ENTRY)。
 */
@RequiredArgsConstructor
public class StationLogicDeviceProfile {

    /** 该 profile 对应的类型槽。 */
    @Getter
    private final StationParamMeta stationParam;

    /** StationDeviceConfigFlow 所在集成坐标。 */
    public String getCoordinate() {
        return AirstationIntegration.COORDINATE;
    }

    /** select_type 的 device_type 值（如 "StationDevice-TH"）。 */
    public String getDeviceType() {
        return stationParam.deviceType;
    }

    /** 多实例类型的实例后缀（单实例 null，driver 据此决定是否提交 device_instance）。 */
    public String getInstance() {
        return stationParam.instance;
    }
}
