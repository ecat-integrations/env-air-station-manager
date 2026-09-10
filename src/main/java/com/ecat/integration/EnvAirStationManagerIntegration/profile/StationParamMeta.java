package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import com.ecat.integration.logicdevice.Meta.DeviceType;

/**
 * ASM 站房设备类型槽元数据（22 类型；多实例类型按 {@code StationDeviceConfigFlow} 的
 * MULTI_INSTANCE_SUFFIXES 展开为多槽，共 37 槽）。
 *
 * <p>每槽 = 一个 airstation 逻辑设备位（{@code logicdevice_station.<type>[.<instance>]}），
 * 对应右侧 Detail 面板一个「配置/更换/移除」单元。单实例类型槽键 = 类型名（如 {@code TH}）；
 * 多实例类型槽键 = {@code 类型.实例}（如 {@code AIR_CONDITIONER.ac1}）——与 URL 路径段
 * {@code /asm-monitor/device/params/{type}} 一致（点号在 path variable 中合法）。
 *
 * <p>uniqueId 公式与 {@code StationDeviceConfigFlow#stepSelectType} 逐行一致
 * （{@code UNIQUE_ID_PREFIX + typeName[.instance]}），实例后缀枚举同
 * MULTI_INSTANCE_SUFFIXES（PM_ZERO_CHECK/CUTTER_CHANGER/PAPER_TAPE=pm10,pm25；
 * AIR_CONDITIONER=ac1,ac2；STANDARD_GAS=so2,co,nox；FILTER_CHANGER=so2,co,o3,nox；
 * CAMERA=1..4；VALVE_GROUP=so2,co,no,o3）。
 *
 * @author coffee
 */
public enum StationParamMeta {

    TH(DeviceType.Station.TH, null, "站房温湿度监测仪"),
    POWER_METER(DeviceType.Station.POWER_METER, null, "智能电力监测仪表"),
    VOLTAGE_REGULATOR(DeviceType.Station.VOLTAGE_REGULATOR, null, "智能稳压电源"),
    UPS(DeviceType.Station.UPS, null, "UPS不间断电源"),
    AIR_CONDITIONER_AC1(DeviceType.Station.AIR_CONDITIONER, "ac1", "空调1"),
    AIR_CONDITIONER_AC2(DeviceType.Station.AIR_CONDITIONER, "ac2", "空调2"),
    EXHAUST_FAN(DeviceType.Station.EXHAUST_FAN, null, "排风扇设备"),
    LIGHTING(DeviceType.Station.LIGHTING, null, "照明设备"),
    ZERO_GAS_RELAY(DeviceType.Station.ZERO_GAS_RELAY, null, "零气继电器"),
    SECURITY_ALARM(DeviceType.Station.SECURITY_ALARM, null, "安防报警监测装置"),
    SAMPLING_TUBE(DeviceType.Station.SAMPLING_TUBE, null, "采样总管监测设备"),
    STANDARD_GAS_SO2(DeviceType.Station.STANDARD_GAS, "so2", "SO2标气"),
    STANDARD_GAS_CO(DeviceType.Station.STANDARD_GAS, "co", "CO标气"),
    STANDARD_GAS_NOX(DeviceType.Station.STANDARD_GAS, "nox", "NOx标气"),
    FILTER_CHANGER_SO2(DeviceType.Station.FILTER_CHANGER, "so2", "SO2滤膜"),
    FILTER_CHANGER_CO(DeviceType.Station.FILTER_CHANGER, "co", "CO滤膜"),
    FILTER_CHANGER_O3(DeviceType.Station.FILTER_CHANGER, "o3", "O3滤膜"),
    FILTER_CHANGER_NOX(DeviceType.Station.FILTER_CHANGER, "nox", "NOx滤膜"),
    CALIBRATOR(DeviceType.Station.CALIBRATOR, null, "动态校准仪"),
    ELECTRONIC_FENCE(DeviceType.Station.ELECTRONIC_FENCE, null, "电子围栏系统"),
    ACCESS_CONTROL(DeviceType.Station.ACCESS_CONTROL, null, "智能门禁系统"),
    CAMERA_1(DeviceType.Station.CAMERA, "1", "摄像头1"),
    CAMERA_2(DeviceType.Station.CAMERA, "2", "摄像头2"),
    CAMERA_3(DeviceType.Station.CAMERA, "3", "摄像头3"),
    CAMERA_4(DeviceType.Station.CAMERA, "4", "摄像头4"),
    CLEANLINESS(DeviceType.Station.CLEANLINESS, null, "站房清洁度检测装置"),
    INDOOR_POLLUTANT(DeviceType.Station.INDOOR_POLLUTANT, null, "室内污染物检测仪"),
    PM_ZERO_CHECK_PM10(DeviceType.Station.PM_ZERO_CHECK, "pm10", "PM10零点检查器"),
    PM_ZERO_CHECK_PM25(DeviceType.Station.PM_ZERO_CHECK, "pm25", "PM2.5零点检查器"),
    CUTTER_CHANGER_PM10(DeviceType.Station.CUTTER_CHANGER, "pm10", "PM10切割器"),
    CUTTER_CHANGER_PM25(DeviceType.Station.CUTTER_CHANGER, "pm25", "PM2.5切割器"),
    PAPER_TAPE_PM10(DeviceType.Station.PAPER_TAPE, "pm10", "PM10纸带记录仪"),
    PAPER_TAPE_PM25(DeviceType.Station.PAPER_TAPE, "pm25", "PM2.5纸带记录仪"),
    VALVE_GROUP_SO2(DeviceType.Station.VALVE_GROUP, "so2", "SO2校准阀"),
    VALVE_GROUP_CO(DeviceType.Station.VALVE_GROUP, "co", "CO校准阀"),
    VALVE_GROUP_NO(DeviceType.Station.VALVE_GROUP, "no", "NO校准阀"),
    VALVE_GROUP_O3(DeviceType.Station.VALVE_GROUP, "o3", "O3校准阀");

    /** airstation 逻辑设备 uniqueId 前缀（与 AirstationIntegration.UNIQUE_ID_PREFIX 同字面，避免运行时依赖）。 */
    public static final String UID_PREFIX = "logicdevice_station.";

    /** 槽类型（airstation device_type，如 "StationDevice-TH"）。 */
    public final String deviceType;
    /** 多实例类型的实例后缀（单实例 null）。 */
    public final String instance;
    /** 展示名（多实例已拼实例名）。 */
    public final String label;

    StationParamMeta(String deviceType, String instance, String label) {
        this.deviceType = deviceType;
        this.instance = instance;
        this.label = label;
    }

    /** 逻辑设备 uniqueId：{@code logicdevice_station.<type小写>[.<instance>]}。 */
    public String getUniqueId() {
        String typeName = deviceType.replace("StationDevice-", "").toLowerCase();
        return instance == null ? UID_PREFIX + typeName : UID_PREFIX + typeName + "." + instance;
    }

    /** 槽键（URL path / 注册键）：类型名[.实例]（如 {@code AIR_CONDITIONER.ac1}）。 */
    public String getType() {
        return instance == null ? deviceType.replace("StationDevice-", "")
                : deviceType.replace("StationDevice-", "") + "." + instance;
    }

    /** 按槽键反查（URL {@code /params/{type}} 解析）。严格模式：未知槽键抛异常（不猜测）。 */
    public static StationParamMeta fromType(String type) {
        for (StationParamMeta p : values()) {
            if (p.getType().equals(type)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知站房设备类型槽: " + type
                + "（须为 22 类型 37 槽之一，见 StationParamMeta）");
    }
}
