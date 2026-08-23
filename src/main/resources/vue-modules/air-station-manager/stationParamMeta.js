// 站房设备类型槽前端元数据：37 槽的展示名 + sidebar 分组（镜像 ADM airParamMeta）。
// 数据源双轨：槽列表/绑定状态动态取 GET /asm-monitor/device/params（后端 StationParamMeta 37 槽，
// param 字段=枚举名）；本文件提供枚举名→{type,label,group} 静态映射（分组/label 前端本地渲染，
// 与后端 StationParamMeta 枚举逐一对照维护——枚举名是两侧 join key）。

// 分组顺序（sidebar 自上而下）
export const GROUP_ORDER = [
  '环境监测',
  '供配电',
  '站房动环',
  '采样与标气',
  '校准与耗材',
  '安防视频',
]

// 枚举名 → 元数据。type = URL 槽键（多实例含实例后缀，与后端 getType() 一致）。
const META = {
  TH: { type: 'TH', group: '环境监测' },
  CLEANLINESS: { type: 'CLEANLINESS', group: '环境监测' },
  INDOOR_POLLUTANT: { type: 'INDOOR_POLLUTANT', group: '环境监测' },
  POWER_METER: { type: 'POWER_METER', group: '供配电' },
  VOLTAGE_REGULATOR: { type: 'VOLTAGE_REGULATOR', group: '供配电' },
  UPS: { type: 'UPS', group: '供配电' },
  AIR_CONDITIONER_AC1: { type: 'AIR_CONDITIONER.ac1', group: '站房动环' },
  AIR_CONDITIONER_AC2: { type: 'AIR_CONDITIONER.ac2', group: '站房动环' },
  EXHAUST_FAN: { type: 'EXHAUST_FAN', group: '站房动环' },
  LIGHTING: { type: 'LIGHTING', group: '站房动环' },
  SAMPLING_TUBE: { type: 'SAMPLING_TUBE', group: '采样与标气' },
  ZERO_GAS_RELAY: { type: 'ZERO_GAS_RELAY', group: '采样与标气' },
  STANDARD_GAS_SO2: { type: 'STANDARD_GAS.so2', group: '采样与标气' },
  STANDARD_GAS_CO: { type: 'STANDARD_GAS.co', group: '采样与标气' },
  STANDARD_GAS_NOX: { type: 'STANDARD_GAS.nox', group: '采样与标气' },
  CALIBRATOR: { type: 'CALIBRATOR', group: '校准与耗材' },
  FILTER_CHANGER_SO2: { type: 'FILTER_CHANGER.so2', group: '校准与耗材' },
  FILTER_CHANGER_CO: { type: 'FILTER_CHANGER.co', group: '校准与耗材' },
  FILTER_CHANGER_O3: { type: 'FILTER_CHANGER.o3', group: '校准与耗材' },
  FILTER_CHANGER_NOX: { type: 'FILTER_CHANGER.nox', group: '校准与耗材' },
  VALVE_GROUP_SO2: { type: 'VALVE_GROUP.so2', group: '校准与耗材' },
  VALVE_GROUP_CO: { type: 'VALVE_GROUP.co', group: '校准与耗材' },
  VALVE_GROUP_NO: { type: 'VALVE_GROUP.no', group: '校准与耗材' },
  VALVE_GROUP_O3: { type: 'VALVE_GROUP.o3', group: '校准与耗材' },
  PM_ZERO_CHECK_PM10: { type: 'PM_ZERO_CHECK.pm10', group: '校准与耗材' },
  PM_ZERO_CHECK_PM25: { type: 'PM_ZERO_CHECK.pm25', group: '校准与耗材' },
  CUTTER_CHANGER_PM10: { type: 'CUTTER_CHANGER.pm10', group: '校准与耗材' },
  CUTTER_CHANGER_PM25: { type: 'CUTTER_CHANGER.pm25', group: '校准与耗材' },
  PAPER_TAPE_PM10: { type: 'PAPER_TAPE.pm10', group: '校准与耗材' },
  PAPER_TAPE_PM25: { type: 'PAPER_TAPE.pm25', group: '校准与耗材' },
  SECURITY_ALARM: { type: 'SECURITY_ALARM', group: '安防视频' },
  ELECTRONIC_FENCE: { type: 'ELECTRONIC_FENCE', group: '安防视频' },
  ACCESS_CONTROL: { type: 'ACCESS_CONTROL', group: '安防视频' },
  CAMERA_1: { type: 'CAMERA.1', group: '安防视频' },
  CAMERA_2: { type: 'CAMERA.2', group: '安防视频' },
  CAMERA_3: { type: 'CAMERA.3', group: '安防视频' },
  CAMERA_4: { type: 'CAMERA.4', group: '安防视频' },
}

// 中文展示名（后端 label 的前端镜像；槽键→名称）
const LABELS = {
  TH: '站房温湿度监测仪',
  POWER_METER: '智能电力监测仪表',
  VOLTAGE_REGULATOR: '智能稳压电源',
  UPS: 'UPS不间断电源',
  AIR_CONDITIONER_AC1: '空调1',
  AIR_CONDITIONER_AC2: '空调2',
  EXHAUST_FAN: '排风扇设备',
  LIGHTING: '照明设备',
  ZERO_GAS_RELAY: '零气继电器',
  SECURITY_ALARM: '安防报警监测装置',
  SAMPLING_TUBE: '采样总管监测设备',
  STANDARD_GAS_SO2: 'SO2标气',
  STANDARD_GAS_CO: 'CO标气',
  STANDARD_GAS_NOX: 'NOx标气',
  FILTER_CHANGER_SO2: 'SO2滤膜',
  FILTER_CHANGER_CO: 'CO滤膜',
  FILTER_CHANGER_O3: 'O3滤膜',
  FILTER_CHANGER_NOX: 'NOx滤膜',
  CALIBRATOR: '动态校准仪',
  ELECTRONIC_FENCE: '电子围栏系统',
  ACCESS_CONTROL: '智能门禁系统',
  CAMERA_1: '摄像头1',
  CAMERA_2: '摄像头2',
  CAMERA_3: '摄像头3',
  CAMERA_4: '摄像头4',
  CLEANLINESS: '站房清洁度检测装置',
  INDOOR_POLLUTANT: '室内污染物检测仪',
  PM_ZERO_CHECK_PM10: 'PM10零点检查器',
  PM_ZERO_CHECK_PM25: 'PM2.5零点检查器',
  CUTTER_CHANGER_PM10: 'PM10切割器',
  CUTTER_CHANGER_PM25: 'PM2.5切割器',
  PAPER_TAPE_PM10: 'PM10纸带记录仪',
  PAPER_TAPE_PM25: 'PM2.5纸带记录仪',
  VALVE_GROUP_SO2: 'SO2校准阀',
  VALVE_GROUP_CO: 'CO校准阀',
  VALVE_GROUP_NO: 'NO校准阀',
  VALVE_GROUP_O3: 'O3校准阀',
}

/** 展示名：枚举名未知时如实显枚举名（不吞、不猜）。 */
export function labelOf(enumName) {
  return LABELS[enumName] || enumName
}

/** URL 槽键（/asm-monitor/device/params/{type} 路径段）。 */
export function typeOf(enumName) {
  return (META[enumName] && META[enumName].type) || enumName
}

/** sidebar 分组名。 */
export function groupOf(enumName) {
  return (META[enumName] && META[enumName].group) || '其他'
}
