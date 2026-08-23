package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import java.util.Collections;
import java.util.Map;

/**
 * 站房 provision profile 全矩阵注册（grep logicdevice-airstation DeviceMappings 实际支持的
 * 类型×厂商×型号矩阵，一个不落；矩阵外不发明）。
 *
 * <p>注册时机：{@code EnvAirStationManagerIntegration.onStart}。stub mapping
 * （Cleanliness/ElectronicFence 的 stub 实现，无真实物理集成坐标）不注册——厂商列表如实为空。
 *
 * <p>全部走 USER_FLOW（{@link SimpleUserFlowProfile}，identityInputs 空=无已知 hydrate 默认，
 * 停首 required 步交用户）——与 ADM 现状一致：ADM 对 saimosen 全家族（SMS8200/8300/8400/8500）
 * 已于 2026-07-27 由 IMPORT_FLOW 改 USER_FLOW，saimosen 集成侧 IMPORT_FLOW 发现入口已移除
 * （其 discovery 绕过 web schema 预填 entryData，不适合经管理集成调用），ASM 不切 IMPORT_FLOW。
 */
public final class StationProfileRegistrar {

    /** 全部 profile 的统一空 identityInputs（无已知默认，交 flow-form 用户填写）。 */
    private static final Map<String, Map<String, String>> NO_DEFAULTS =
            Collections.emptyMap();

    private final StationProvisionProfileRegistry registry;

    public StationProfileRegistrar(StationProvisionProfileRegistry registry) {
        this.registry = registry;
    }

    /** 全矩阵注册（幂等：重复 register 同键覆盖）。 */
    public void registerAll() {
        String saimosen = "com.ecat:integration-saimosen";

        // TH：aogan AGH3485 / saimosen SMS8910(V1/V2)
        user(StationParamMeta.TH, "com.ecat:integration-aogan", "AGH3485");
        user(StationParamMeta.TH, saimosen, "SMS8910");
        user(StationParamMeta.TH, saimosen, "SMS8910V2");
        // 电力监测仪表：chko DTS8666 / saimosen QC
        user(StationParamMeta.POWER_METER, "com.ecat:integration-chko", "DTS8666");
        user(StationParamMeta.POWER_METER, saimosen, "SMS8910");
        user(StationParamMeta.POWER_METER, saimosen, "SMS8910V2");
        // 稳压电源：saimosen SmartPowerStabilizer(IRP0501B) / QCV2(SMS8910V2)
        user(StationParamMeta.VOLTAGE_REGULATOR, saimosen, "IRP0501B");
        user(StationParamMeta.VOLTAGE_REGULATOR, saimosen, "SMS8910V2");
        // UPS：santak EDX / zhengxin HCUPS / saimosen QC
        user(StationParamMeta.UPS, "com.ecat:integration-santak", "EDX");
        user(StationParamMeta.UPS, "com.ecat:integration-zhengxin", "HCUPS");
        user(StationParamMeta.UPS, saimosen, "SMS8910");
        user(StationParamMeta.UPS, saimosen, "SMS8910V2");
        // 空调：szzht RACC2 / saimosen QC
        user(StationParamMeta.AIR_CONDITIONER_AC1, "com.ecat:integration-szzht", "RACC2");
        user(StationParamMeta.AIR_CONDITIONER_AC1, saimosen, "SMS8910");
        user(StationParamMeta.AIR_CONDITIONER_AC1, saimosen, "SMS8910V2");
        user(StationParamMeta.AIR_CONDITIONER_AC2, "com.ecat:integration-szzht", "RACC2");
        user(StationParamMeta.AIR_CONDITIONER_AC2, saimosen, "SMS8910");
        user(StationParamMeta.AIR_CONDITIONER_AC2, saimosen, "SMS8910V2");
        // 排风扇：saimosen QC
        user(StationParamMeta.EXHAUST_FAN, saimosen, "SMS8910");
        user(StationParamMeta.EXHAUST_FAN, saimosen, "SMS8910V2");
        // 照明：saimosen QC
        user(StationParamMeta.LIGHTING, saimosen, "SMS8910");
        user(StationParamMeta.LIGHTING, saimosen, "SMS8910V2");
        // 零气继电器：saimosen QC
        user(StationParamMeta.ZERO_GAS_RELAY, saimosen, "SMS8910");
        user(StationParamMeta.ZERO_GAS_RELAY, saimosen, "SMS8910V2");
        // 安防报警：juyingele DAM0888A / saimosen QC
        user(StationParamMeta.SECURITY_ALARM, "com.ecat:integration-juyingele", "DAM0888A");
        user(StationParamMeta.SECURITY_ALARM, saimosen, "SMS8910");
        user(StationParamMeta.SECURITY_ALARM, saimosen, "SMS8910V2");
        // 采样总管：saimosen QC / SmartPole
        user(StationParamMeta.SAMPLING_TUBE, saimosen, "SMS8910");
        user(StationParamMeta.SAMPLING_TUBE, saimosen, "SMS8910V2");
        user(StationParamMeta.SAMPLING_TUBE, saimosen, "SampleTube");
        // 标气钢瓶：thermofisher 146i / saimosen SMS8600V2 + QC
        for (StationParamMeta p : new StationParamMeta[]{StationParamMeta.STANDARD_GAS_SO2,
                StationParamMeta.STANDARD_GAS_CO, StationParamMeta.STANDARD_GAS_NOX}) {
            user(p, "com.ecat:integration-thermofisher", "146i");
            user(p, saimosen, "SMS8600V2");
            user(p, saimosen, "SMS8910");
            user(p, saimosen, "SMS8910V2");
        }
        // 滤膜更换：cecep TRAMC500 / saimosen QC
        for (StationParamMeta p : new StationParamMeta[]{StationParamMeta.FILTER_CHANGER_SO2,
                StationParamMeta.FILTER_CHANGER_CO, StationParamMeta.FILTER_CHANGER_O3,
                StationParamMeta.FILTER_CHANGER_NOX}) {
            user(p, "com.ecat:integration-cecep", "TRAMC500");
            user(p, saimosen, "SMS8910");
            user(p, saimosen, "SMS8910V2");
        }
        // 动态校准仪：7 型号
        user(StationParamMeta.CALIBRATOR, "com.ecat:integration-thermofisher", "146i");
        user(StationParamMeta.CALIBRATOR, "com.ecat:integration-teledyne-api", "T700");
        user(StationParamMeta.CALIBRATOR, "com.ecat:integration-tianhong", "TH2008K");
        user(StationParamMeta.CALIBRATOR, "com.ecat:integration-tianhong", "TH2008T");
        user(StationParamMeta.CALIBRATOR, "com.ecat:integration-tjtongyangkeji", "TY-AQMS-45");
        user(StationParamMeta.CALIBRATOR, "com.ecat:integration-sailhero", "XHCAL2000BV3");
        user(StationParamMeta.CALIBRATOR, saimosen, "SMS8600V1");
        user(StationParamMeta.CALIBRATOR, saimosen, "SMS8600V2");
        // 门禁：hikvision DS-K1T670M / zkteco TDB08M
        user(StationParamMeta.ACCESS_CONTROL, "com.ecat:integration-hikvision", "DS-K1T670M");
        user(StationParamMeta.ACCESS_CONTROL, "com.ecat:integration-zkteco", "TDB08M");
        // 摄像头：benmai MBELBH16P
        for (StationParamMeta p : new StationParamMeta[]{StationParamMeta.CAMERA_1,
                StationParamMeta.CAMERA_2, StationParamMeta.CAMERA_3, StationParamMeta.CAMERA_4}) {
            user(p, "com.ecat:integration-benmai", "MBELBH16P");
        }
        // 清洁度：saimosen QC（stub 不注册）
        user(StationParamMeta.CLEANLINESS, saimosen, "SMS8910");
        user(StationParamMeta.CLEANLINESS, saimosen, "SMS8910V2");
        // 室内污染物：rkonfly / saimosen QC
        user(StationParamMeta.INDOOR_POLLUTANT, "com.ecat:integration-rkonfly", "CompositeAirQualityDetector");
        user(StationParamMeta.INDOOR_POLLUTANT, saimosen, "SMS8910");
        user(StationParamMeta.INDOOR_POLLUTANT, saimosen, "SMS8910V2");
        // PM 零点检查：saimosen QC
        for (StationParamMeta p : new StationParamMeta[]{StationParamMeta.PM_ZERO_CHECK_PM10,
                StationParamMeta.PM_ZERO_CHECK_PM25}) {
            user(p, saimosen, "SMS8910");
            user(p, saimosen, "SMS8910V2");
        }
        // 切割器更换：qdrgdz QGQGHZZ
        for (StationParamMeta p : new StationParamMeta[]{StationParamMeta.CUTTER_CHANGER_PM10,
                StationParamMeta.CUTTER_CHANGER_PM25}) {
            user(p, "com.ecat:integration-qdrgdz", "QGQGHZZ");
        }
        // 纸带记录仪：sailhero XHPM3000EV3 / XHPM2000EV2
        for (StationParamMeta p : new StationParamMeta[]{StationParamMeta.PAPER_TAPE_PM10,
                StationParamMeta.PAPER_TAPE_PM25}) {
            user(p, "com.ecat:integration-sailhero", "XHPM3000EV3");
            user(p, "com.ecat:integration-sailhero", "XHPM2000EV2");
        }
        // 校准阀门组：saimosen QC
        for (StationParamMeta p : new StationParamMeta[]{StationParamMeta.VALVE_GROUP_SO2,
                StationParamMeta.VALVE_GROUP_CO, StationParamMeta.VALVE_GROUP_NO,
                StationParamMeta.VALVE_GROUP_O3}) {
            user(p, saimosen, "SMS8910");
            user(p, saimosen, "SMS8910V2");
        }
        // 电子围栏：仅 stub mapping，无真实集成坐标——不注册（厂商列表如实为空）
    }

    private void user(StationParamMeta param, String coordinate, String model) {
        registry.register(SimpleUserFlowProfile.builder()
                .stationParam(param)
                .coordinate(coordinate)
                .model(model)
                .identityInputs(NO_DEFAULTS)
                .build());
    }
}
