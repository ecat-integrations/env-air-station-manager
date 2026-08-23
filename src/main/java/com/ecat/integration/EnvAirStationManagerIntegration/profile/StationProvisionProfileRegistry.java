package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站房 provision profile 注册表（与 ADM 同构）。
 *
 * <p>两级 Map：{@code 站房设备类型(deviceType) → (coordinate-model) → profile}。
 * <b>厂商列表唯一来源</b>：{@link #getByType} 只返回已注册的 profile（未注册即空，不兜底
 * LogicMappingManager）。注册时机：{@code EnvAirStationManagerIntegration.onStart}（经
 * {@link StationProfileRegistrar} 全矩阵注册）。
 */
public class StationProvisionProfileRegistry {

    private static final StationProvisionProfileRegistry INSTANCE = new StationProvisionProfileRegistry();

    public static StationProvisionProfileRegistry getInstance() {
        return INSTANCE;
    }

    private StationProvisionProfileRegistry() {
    }

    /** 测试用独立实例（生产走 {@link #getInstance()} 单例；测试须隔离注册状态避免互染）。 */
    static StationProvisionProfileRegistry createFresh() {
        return new StationProvisionProfileRegistry();
    }

    /** deviceType → (coordinate-model) → profile */
    private final Map<String, Map<String, StationProvisionProfile>> profiles = new LinkedHashMap<>();

    /** 注册一个 profile（复合键 = deviceType + coordinate-model）。 */
    public void register(StationProvisionProfile profile) {
        String type = profile.getStationParam().deviceType;
        String key = profile.getCoordinate() + "-" + profile.getModel();
        profiles.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(key, profile);
    }

    /** 精确查 profile（provision 用；未注册返 null，service 层严格报错）。 */
    public StationProvisionProfile get(String deviceType, String coordinate, String model) {
        Map<String, StationProvisionProfile> byType = profiles.get(deviceType);
        return byType == null ? null : byType.get(coordinate + "-" + model);
    }

    /** 列该类型槽可选的厂家型号（厂商列表唯一来源；未注册返空列表）。 */
    public List<VendorOption> getByType(StationParamMeta param) {
        Map<String, StationProvisionProfile> byType = profiles.get(param.deviceType);
        if (byType == null || byType.isEmpty()) {
            return Collections.emptyList();
        }
        List<VendorOption> list = new ArrayList<>();
        for (StationProvisionProfile p : byType.values()) {
            list.add(new VendorOption(p.getCoordinate(), p.getModel(), p.getModel()));
        }
        return list;
    }

    /** 厂家型号选项（coordinate/model/label）。 */
    @Value
    public static final class VendorOption {
        String coordinate;
        String model;
        String label;
    }
}
