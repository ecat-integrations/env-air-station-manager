package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import com.ecat.integration.logicdevice.Meta.DeviceType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型槽元数据测试：22 类型全覆盖（枚举 vs DeviceType.Station.values）、37 槽、uniqueId 公式、
 * fromType 往返 + 严格拒绝未知槽键。
 */
class StationParamMetaTest {

    @Test
    void coversAll22DeviceTypes() {
        Set<String> types = new HashSet<>();
        for (StationParamMeta p : StationParamMeta.values()) {
            types.add(p.deviceType);
        }
        assertEquals(new HashSet<>(java.util.Arrays.asList(DeviceType.Station.values())), types,
                "22 站房设备类型须全部有槽（多实例类型多槽）");
    }

    @Test
    void multiInstanceTypesExpandToDeclaredSlots() {
        assertEquals(37, StationParamMeta.values().length);
        // 多实例展开抽查：空调 ac1/ac2、标气 so2/co/nox、摄像头 1..4、滤膜 so2/co/o3/nox、阀组 so2/co/no/o3
        assertEquals("logicdevice_station.air_conditioner.ac1",
                StationParamMeta.AIR_CONDITIONER_AC1.getUniqueId());
        assertEquals("logicdevice_station.standard_gas.nox",
                StationParamMeta.STANDARD_GAS_NOX.getUniqueId());
        assertEquals("logicdevice_station.camera.4", StationParamMeta.CAMERA_4.getUniqueId());
        assertEquals("logicdevice_station.filter_changer.o3",
                StationParamMeta.FILTER_CHANGER_O3.getUniqueId());
        assertEquals("logicdevice_station.valve_group.no", StationParamMeta.VALVE_GROUP_NO.getUniqueId());
        // 单实例：无实例后缀
        assertEquals("logicdevice_station.th", StationParamMeta.TH.getUniqueId());
        assertEquals("logicdevice_station.calibrator", StationParamMeta.CALIBRATOR.getUniqueId());
    }

    @Test
    void fromTypeRoundTripsEverySlotAndRejectsUnknown() {
        for (StationParamMeta p : StationParamMeta.values()) {
            assertEquals(p, StationParamMeta.fromType(p.getType()));
        }
        assertThrows(IllegalArgumentException.class, () -> StationParamMeta.fromType("NO_SUCH_TYPE"));
        assertThrows(IllegalArgumentException.class, () -> StationParamMeta.fromType("AIR_CONDITIONER.ac3"));
    }

    @Test
    void uniqueIdsAreUnique() {
        Set<String> uids = new HashSet<>();
        for (StationParamMeta p : StationParamMeta.values()) {
            assertTrue(uids.add(p.getUniqueId()), "uniqueId 冲突: " + p.getUniqueId());
        }
    }

    @Test
    void allSlotsHaveNonNullInstanceOnlyForMultiInstance() {
        // 多实例槽 instance 非空；单实例槽 instance 为 null（driver 据此决定是否提交 device_instance）
        assertNull(StationParamMeta.TH.instance);
        assertEquals("ac2", StationParamMeta.AIR_CONDITIONER_AC2.instance);
    }
}
