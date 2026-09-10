package com.ecat.integration.EnvAirStationManagerIntegration.profile.logicdevice;

import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationParamMeta;

import java.util.EnumMap;
import java.util.Map;

/**
 * 站房 logic device profile 注册表（每类型槽一个实例，共 37）。
 * 与物理 {@code StationProvisionProfileRegistry}（厂家下拉用）分离——logic profile 是内部的。
 *
 * @author coffee
 */
public class StationLogicDeviceProfileRegistry {

    private static final StationLogicDeviceProfileRegistry INSTANCE = new StationLogicDeviceProfileRegistry();

    private final Map<StationParamMeta, StationLogicDeviceProfile> profiles =
            new EnumMap<>(StationParamMeta.class);

    private StationLogicDeviceProfileRegistry() {
        for (StationParamMeta param : StationParamMeta.values()) {
            profiles.put(param, new StationLogicDeviceProfile(param));
        }
    }

    public static StationLogicDeviceProfileRegistry getInstance() {
        return INSTANCE;
    }

    /** 按类型槽取 logic device profile。严格模式：无 profile 抛异常（37 槽均注册，不应缺失）。 */
    public StationLogicDeviceProfile get(StationParamMeta param) {
        StationLogicDeviceProfile p = profiles.get(param);
        if (p == null) {
            throw new IllegalStateException("未注册的站房 logic device profile: " + param);
        }
        return p;
    }
}
