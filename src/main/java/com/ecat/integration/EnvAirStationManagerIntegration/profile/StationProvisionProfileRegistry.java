package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
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
 *
 * @author coffee
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

    /**
     * 类型槽 StationParamMeta → (coordinate-model) → profile。
     *
     * <p>键必须细到槽实例（非 deviceType）：多实例类型（切割器 pm10/pm25、空调 ac1/ac2 等）
     * 共享同一 deviceType——若按 deviceType 索引，同厂商型号的多槽注册互相覆盖，get() 恒返回
     * 后注册槽的 profile，FlowContext.stationParam 随之错槽（在 pm10 槽配置绑到 pm25，
     * bugs/bug-record-20260902-*）。厂商型号矩阵对同 deviceType 的各实例是全量同构注册，
     * 故按槽索引不影响厂商列表结果。
     */
    private final Map<StationParamMeta, Map<String, StationProvisionProfile>> profiles =
            new EnumMap<>(StationParamMeta.class);

    /** 注册一个 profile（复合键 = 类型槽 + coordinate-model）。 */
    public void register(StationProvisionProfile profile) {
        StationParamMeta slot = profile.getStationParam();
        String key = profile.getCoordinate() + "-" + profile.getModel();
        profiles.computeIfAbsent(slot, k -> new LinkedHashMap<>()).put(key, profile);
    }

    /** 精确查 profile（provision 用；未注册返 null，service 层严格报错）。键含槽实例。 */
    public StationProvisionProfile get(StationParamMeta param, String coordinate, String model) {
        Map<String, StationProvisionProfile> bySlot = profiles.get(param);
        return bySlot == null ? null : bySlot.get(coordinate + "-" + model);
    }

    /** 列该类型槽可选的厂家型号（厂商列表唯一来源；未注册返空列表）。 */
    public List<VendorOption> getByType(StationParamMeta param) {
        Map<String, StationProvisionProfile> bySlot = profiles.get(param);
        if (bySlot == null || bySlot.isEmpty()) {
            return Collections.emptyList();
        }
        List<VendorOption> list = new ArrayList<>();
        for (StationProvisionProfile p : bySlot.values()) {
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
