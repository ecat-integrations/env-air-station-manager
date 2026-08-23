package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import com.ecat.integration.logicdevice.Meta.DeviceType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile 全矩阵注册测试：22 类型除 ELECTRONIC_FENCE（仅 stub mapping 无真实集成）外
 * 每类型至少一个厂商；注册键 (deviceType, coordinate, model) 可精确回查；矩阵外不发明。
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
        // 多实例槽共享 deviceType 注册键（ac1/ac2 都能查到空调矩阵）
        for (StationParamMeta p : StationParamMeta.values()) {
            for (StationProvisionProfileRegistry.VendorOption v : registry.getByType(p)) {
                assertNotNull(registry.get(p.deviceType, v.getCoordinate(), v.getModel()),
                        "注册键可精确回查: " + p.getType() + " / " + v.getCoordinate() + " / " + v.getModel());
            }
        }
    }
}
