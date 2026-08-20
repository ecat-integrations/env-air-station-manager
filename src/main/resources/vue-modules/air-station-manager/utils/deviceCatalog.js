// ASM 站房设备目录——uid 类型 → 分组 + 核心参数映射（总览页分组瓦片墙的唯一映射真相源）。
//
// uid 形如 logicdevice_station.{type} 或 logicdevice_station.{type}.{instance}（camera.1 / standard_gas.co），
// type = 去掉前缀后的第一段。分组共 7 组（含「其他」兜底），组顺序即瓦片墙渲染顺序。
//
// 核心参数（coreAttrs）= 该类型瓦片上直接展示的 1~2 个 attrId；瓦片渲染 displayName（后端 snapshot/SSE
// 已带中文参数名），此处只声明 attrId 不含中文名——避免双份映射漂移。
//
// 设备中文名优先用后端 snapshot 的 device.displayName（entry name），本表 name 仅作后端缺席时的兜底。

/** 分组定义（key 唯一；order 数组控制渲染顺序）。 */
export const DEVICE_GROUPS = [
  { key: 'env', name: '温湿度环境' },
  { key: 'power', name: '供电' },
  { key: 'climate', name: '环境调节' },
  { key: 'security', name: '安防' },
  { key: 'consumable', name: '采样耗材' },
  { key: 'calibration', name: '标气校准' },
  { key: 'other', name: '其他' },
]

/**
 * 类型目录：type → { group, name(兜底中文名), coreAttrs(瓦片核心参数 attrId，1~2 个) }。
 * 未收录的类型（airstation 新增设备类型）落「其他」组、瓦片只显在线态不显参数（显式兜底非静默丢弃）。
 */
export const DEVICE_TYPE_CATALOG = {
  // ── 温湿度环境 ──
  th: { group: 'env', name: '温湿度计', coreAttrs: ['temperature', 'humidity'] },
  indoor_pollutant: { group: 'env', name: '室内污染物检测仪', coreAttrs: ['pm25_indoor', 'pm10_indoor'] },
  cleanliness: { group: 'env', name: '洁净度检测仪', coreAttrs: ['pm10_indoor', 'cumulative_dust'] },

  // ── 供电 ──
  power_meter: { group: 'power', name: '电能表', coreAttrs: ['voltage_a', 'current_a'] },
  ups: { group: 'power', name: 'UPS 电源', coreAttrs: ['battery_voltage', 'load_percent'] },
  voltage_regulator: { group: 'power', name: '稳压器', coreAttrs: ['voltage_ch1', 'current_ch1'] },

  // ── 环境调节 ──
  air_conditioner: { group: 'climate', name: '空调', coreAttrs: ['setpoint_temp', 'running_mode'] },
  exhaust_fan: { group: 'climate', name: '排风扇', coreAttrs: ['speed'] },
  lighting: { group: 'climate', name: '照明', coreAttrs: ['switch_status'] },

  // ── 安防 ──
  camera: { group: 'security', name: '视频监控', coreAttrs: ['ai_running'] },
  access_control: { group: 'security', name: '门禁', coreAttrs: ['lock_status'] },
  security_alarm: { group: 'security', name: '安防报警主机', coreAttrs: ['smoke_1', 'water_leak'] },
  electronic_fence: { group: 'security', name: '电子围栏', coreAttrs: ['fence_status'] },

  // ── 采样耗材 ──
  paper_tape: { group: 'consumable', name: '纸带采样器', coreAttrs: ['remaining_spots'] },
  cutter_changer: { group: 'consumable', name: '切割器切换器', coreAttrs: ['cutter_remaining'] },
  filter_changer: { group: 'consumable', name: '滤膜切换器', coreAttrs: ['filter_remaining'] },
  sampling_tube: { group: 'consumable', name: '采样管', coreAttrs: ['temperature', 'flow_velocity'] },

  // ── 标气校准 ──
  standard_gas: { group: 'calibration', name: '标准气源', coreAttrs: ['pressure_remaining', 'gas_leak_data'] },
  calibrator: { group: 'calibration', name: '校准仪', coreAttrs: ['current_gas_type', 'span_flow_realtime'] },
  zero_gas_relay: { group: 'calibration', name: '零气发生继电器', coreAttrs: ['relay_status'] },
  valve_group: { group: 'calibration', name: '阀门组', coreAttrs: ['valve_status'] },
  pm_zero_check: { group: 'calibration', name: 'PM 零点校准', coreAttrs: ['flow_rate'] },
}

/** uid 前缀（与后端 STATION_UID_PREFIX 同源）。 */
const UID_PREFIX = 'logicdevice_station.'

/**
 * 从 uid 解析设备类型（第一段；instance 段剥掉）。
 * @param {string} uid logicdevice_station.*
 * @returns {string} type（th / camera / standard_gas ...）
 */
export function deviceTypeOf(uid) {
  if (!uid || !uid.startsWith(UID_PREFIX)) return ''
  return uid.slice(UID_PREFIX.length).split('.')[0]
}

/**
 * 取类型目录项（未收录返「其他」组空参数项——显式兜底：瓦片仍展示在线态）。
 * @param {string} uid
 * @returns {{group:string, name:string, coreAttrs:string[]}}
 */
export function catalogOf(uid) {
  const type = deviceTypeOf(uid)
  return DEVICE_TYPE_CATALOG[type]
    || { group: 'other', name: type || uid, coreAttrs: [] }
}
