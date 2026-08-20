// 受控设备类型常量：总览抽屉「设备控制」按钮显隐的唯一判定源。
//
// 维护口径：增删受控设备类型改这里，须与 DM（env-device-manager）env_device_settings 配置保持同步
//（DM 无配置则控制页无该 card；此处常量决定的是总览抽屉按钮显隐，不决定控制页 card 数据源）。
// 类型 key 与 control/devices/ 各设备模块 typeKey 同一体系。
//
// uid → 类型段提取（typeOfUid）与 catalog/devices 模块同源：唯一实现放本文件，
// devices/*.js 与总览页共用，勿在别处再写一份。

/** 受控设备类型（uid 第二段：logicdevice_station.{type}[.{instance}]）。 */
export const CONTROLLABLE_TYPES = [
  'air_conditioner',   // 空调（ac1/ac2 同类型天然都命中）
  'lighting',          // 室内灯光
  'exhaust_fan',       // 排风扇
  'access_control',    // 门禁
  'sampling_tube',     // 智能采样管
  'voltage_regulator', // 智能稳压电源
]

/** 提取 uid 类型段：logicdevice_station.{type}.{instance} → {type}；非该前缀返回空串。 */
export function typeOfUid(uid) {
  if (typeof uid !== 'string' || !uid.startsWith('logicdevice_station.')) return ''
  return uid.slice('logicdevice_station.'.length).split('.')[0]
}

/** 该 uid 是否可控（类型段 ∈ CONTROLLABLE_TYPES）——总览抽屉按钮显隐判定。 */
export function isControllableUid(uid) {
  return CONTROLLABLE_TYPES.includes(typeOfUid(uid))
}
