// 设备 → 图标 kind 推断（纯函数，可单测）。
//
// 判定顺序（三条，改顺序会改变页面观感，改动前先想清楚）：
//   ① 物理设备 class 是「质控仪」(air.monitor.qc) → qc 图标。
//      理由：质控仪是一台**多槽复用的一体化仪表**（同一台 SMS8910 可能同时服务温湿度 / 空调 /
//      照明 / 零气继电器…）。若按槽画图标，同一台设备在图上会长出好几种样子；按它自己的形态画才对。
//   ② 类型槽优先 —— 站房设备配置页的每一行就是一个「类型槽」，槽本身就是设备类别
//      （AIR_CONDITIONER.ac1 → air_conditioner，PM_ZERO_CHECK.pm10 → pm_zero_check）。
//      这是最稳定的信号：同一个槽永远画同一个图形，不随厂家型号变化。
//   ③ 槽未知时按物理设备 class 兜底（少数槽将来接非本类设备时仍能画对大类）。
//
// 命名注意：本文件不能叫 `stationGlyph.js` / `glyph.js` —— webpack 的 resolve.extensions
// 含 ['.vue', '.js']，大小写不敏感的 Windows 上 `./stationGlyph` 会命中 StationGlyph.vue，
// 把 SFC 当工具模块加载（编译只告警、运行期 glyphKind 为 undefined）。
import { STATION_GLYPHS } from './stationGlyphDefs.js'

/** 类型槽键（小写、去实例后缀）→ kind。key 与 utils/deviceCatalog.js 的 type 同名。 */
const TYPE_KIND = {
  th: 'th',
  cleanliness: 'cleanliness',
  indoor_pollutant: 'indoor_pollutant',
  power_meter: 'power_meter',
  voltage_regulator: 'voltage_regulator',
  ups: 'ups',
  air_conditioner: 'air_conditioner',
  exhaust_fan: 'exhaust_fan',
  lighting: 'lighting',
  sampling_tube: 'sampling_tube',
  zero_gas_relay: 'zero_gas_relay',
  standard_gas: 'standard_gas',
  calibrator: 'calibrator',
  filter_changer: 'filter_changer',
  valve_group: 'valve_group',
  pm_zero_check: 'pm_zero_check',
  cutter_changer: 'cutter_changer',
  paper_tape: 'paper_tape',
  security_alarm: 'security_alarm',
  electronic_fence: 'electronic_fence',
  access_control: 'access_control',
  camera: 'camera',
  // 将来若新增「质控仪」类型槽，槽键直接叫 qc / qc_instrument 都能命中
  qc: 'qc',
  qc_instrument: 'qc',
}

/** 物理设备 class（DeviceClasses）→ kind。用于 ① 质控仪特判与 ③ 槽未知时的兜底。 */
const CLASS_KIND = {
  'air.monitor.qc': 'qc',
  'air.conditioner': 'air_conditioner',
  'power.sensor': 'power_meter',
  'power.ups': 'ups',
  'power.supply.stabilizer': 'voltage_regulator',
  'weather.sensor': 'th',
  'sample.tube': 'sampling_tube',
  'air.filter.membrane': 'filter_changer',
  'air.monitor.calibrator': 'calibrator',
  'air.monitor.zero_gas': 'zero_gas_relay',
  'air.monitor.pm': 'indoor_pollutant',
  'air.monitor.pm.qc': 'pm_zero_check',
}

/** 槽类型键：'AIR_CONDITIONER.ac1' → 'air_conditioner'；'TH' → 'th'。 */
export function slotKindKey(slotType) {
  return String(slotType || '').toLowerCase().split('.')[0]
}

/**
 * @param {object} p
 * @param {string} [p.slotType]  类型槽键（StationParamMeta.getType()，如 AIR_CONDITIONER.ac1）
 * @param {string} [p.className] 物理设备 entry.data.class（如 air.conditioner / air.monitor.qc）
 * @param {boolean} [p.multiSlot] 该设备是否被多个类型槽共用（仅用于备注，不改变判定）
 * @returns {string} STATION_GLYPHS 的 kind（未知时 'generic'）
 */
export function glyphKindOf({ slotType = '', className = '', multiSlot = false } = {}) {
  const cls = String(className || '').toLowerCase()
  // ① 质控仪：一体化仪表，按自身形态画（不受槽类型影响）
  if (CLASS_KIND[cls] === 'qc') return 'qc'
  // ② 槽类型：一类槽一种图标
  const bySlot = TYPE_KIND[slotKindKey(slotType)]
  if (bySlot && STATION_GLYPHS[bySlot]) return bySlot
  // ③ class 兜底
  const byClass = CLASS_KIND[cls]
  if (byClass && STATION_GLYPHS[byClass]) return byClass
  return 'generic'
}
