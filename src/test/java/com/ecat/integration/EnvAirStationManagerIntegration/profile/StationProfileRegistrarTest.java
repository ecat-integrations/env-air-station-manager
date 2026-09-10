package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import com.ecat.integration.logicdevice.Meta.DeviceType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile 全矩阵注册测试：22 类型除 ELECTRONIC_FENCE（仅 stub mapping 无真实集成）外
 * 每类型至少一个厂商；注册键 (deviceType, coordinate, model) 可精确回查；矩阵外不发明。
 *
 * @author coffee
 */
class StationProfileRegistrarTest {

    private final StationProvisionProfileRegistry registry = StationProvisionProfileRegistry.createFresh();

    {
        new StationProfileRegistrar(registry).registerAll();
    }

    @Test
    void everyTypeHasVendorExceptElectronicFenceStubOnly() {
        Set<String> expectEmpty = new HashSet<>(Arrays.asList(DeviceType.Station.ELECTRONIC_FENCE));
        for (StationParamMeta p : StationParamMeta.values()) {
            int vendors = registry.getByType(p).size();
            if (expectEmpty.contains(p.deviceType)) {
                assertEquals(0, vendors, "仅 stub mapping 的类型厂商列表应为空: " + p.getType());
            } else {
                assertTrue(vendors > 0, "类型槽须至少一个已注册厂商: " + p.getType());
            }
        }
    }

    @Test
    void registeredProfileRoundTripsByCoordinateModel() {
        // 按槽实例回查（多实例槽各自有全量厂商矩阵）
        for (StationParamMeta p : StationParamMeta.values()) {
            for (StationProvisionProfileRegistry.VendorOption v : registry.getByType(p)) {
                assertNotNull(registry.get(p, v.getCoordinate(), v.getModel()),
                        "注册键可精确回查: " + p.getType() + " / " + v.getCoordinate() + " / " + v.getModel());
            }
        }
    }

    /**
     * 多实例槽 profile 隔离（bugs/bug-record-20260902 绑错槽根因）：同 deviceType 的
     * 多实例槽（如 CUTTER_CHANGER pm10/pm25）各自注册同厂商型号 profile——按槽取回的
     * profile 携带的 stationParam 必须就是该槽。曾按 deviceType 索引，后注册槽覆盖
     * 前者：pm10 槽发起的配置 FlowContext 拿到 pm25 的 profile → 绑定落到 pm25。
     * 逐槽断言，37 槽全覆盖（含全部多实例类型）。
     */
    @Test
    void multiInstanceSlotProfilesCarryOwnStationParam() {
        for (StationParamMeta p : StationParamMeta.values()) {
            for (StationProvisionProfileRegistry.VendorOption v : registry.getByType(p)) {
                StationProvisionProfile profile = registry.get(p, v.getCoordinate(), v.getModel());
                assertNotNull(profile, "槽 profile 须存在: " + p.getType());
                assertSame(p, profile.getStationParam(),
                        "槽 profile 携带的 stationParam 必须就是发起槽（共享 deviceType 不得串槽）: "
                                + p.getType() + " 实际=" + profile.getStationParam().getType());
            }
        }
    }
}
