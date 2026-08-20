// 设备控制目录：DM（env-device-manager）GET /device/control/settings 拉取 + 缓存 + uid→card 组装。
//
// 数据分两源（设计定稿，勿合并）：DM 配置供「哪些设备可控 + 控件形态（displayType/options/min/max/step）+
// 命令 key 当前值」；ASM snapshot（unit=custom）供「card 标题中文名 + 数值类实时值/单位」。
// 枚举类（command/select）当前值以 DM 的 key 为准（SSE 帧只带中文 label，经 label→key 反查合并）。
//
// 拉取失败（403 无权限 / 404 端点缺失）由调用方显式呈现空态，本模块抛错不静默吞。

import request from '@/utils/request'
import airConditioner from './devices/airConditioner'
import lighting from './devices/lighting'
import exhaustFan from './devices/exhaustFan'
import accessControl from './devices/accessControl'
import samplingTube from './devices/samplingTube'
import voltageRegulator from './devices/voltageRegulator'

/** 设备显示模块注册表（每类设备一个 js；未匹配走通用兜底：DM 配置序 + rows 布局）。 */
const DEVICE_MODULES = [airConditioner, lighting, exhaustFan, accessControl, samplingTube, voltageRegulator]

const GENERIC_MODULE = { typeKey: '__generic__', matches: () => false, layout: 'rows' }

/** uid → 设备显示模块（排序/布局提示）。 */
export function deviceModuleFor(uid) {
  return DEVICE_MODULES.find(m => m.matches(uid)) || GENERIC_MODULE
}

/** 按 device module 的 attrOrder 排 card 控件项（未列出的排尾部保 DM 序）。 */
export function orderCommands(commands, uid) {
  const mod = deviceModuleFor(uid)
  if (!mod.attrOrder) return commands
  const idx = id => { const i = mod.attrOrder.indexOf(id); return i < 0 ? mod.attrOrder.length : i }
  return [...commands].sort((a, b) => idx(a.attributeId) - idx(b.attributeId))
}

/** DM 受控设备配置端点（ruoyi 上下文 8080，权限 device:device_info:list）。 */
const CONTROL_SETTINGS_URL = '/device/control/settings'

/** 模块级缓存（monitor 抽屉按钮与 device_control 页共用一次拉取；force=true 强制重取）。 */
let cachedPromise = null

/**
 * 拉取 DM 受控设备配置（带缓存）。
 * @returns {Promise<Array>} DM rows 原始数组（[{deviceType, deviceId, logoType, online, commandList, title, settingId}]）
 * @throws 网络错 / code!=200 / rows 缺失时抛 Error（页面显空态文案，不静默空白）
 */
export function fetchControlSettings(force = false) {
  if (!force && cachedPromise) return cachedPromise
  cachedPromise = (async () => {
    const res = await request({ url: CONTROL_SETTINGS_URL, method: 'get' })
    // ruoyi TableDataInfo：HTTP 恒 200，真实状态看 body code（401/403/200）
    if (!res || (res.code != null && res.code !== 200)) {
      throw new Error((res && res.msg) || `DM 配置拉取失败 code=${res && res.code}`)
    }
    if (!Array.isArray(res.rows)) throw new Error('DM 配置响应缺 rows')
    return res.rows
  })()
  cachedPromise.catch(() => { cachedPromise = null })  // 失败不缓存，下次重试
  return cachedPromise
}

/** 可控 uid 集合（monitor 抽屉按钮显隐判定；失败抛错由调用方决定降级为不显按钮）。 */
export async function loadControllableUids() {
  const rows = await fetchControlSettings()
  return new Set(rows.map(r => r.deviceId))
}

/** DM commandList 行 → 归一化控件项（镜像 DM DevicePanel updateDeviceStates 的补默认值口径）。 */
function normalizeCommand(cmd) {
  return {
    attributeId: cmd.attributeId,
    commandName: cmd.commandName,
    displayType: cmd.displayType,
    value: cmd.value,                                     // 枚举 key / 数值串（DM 视角的当前值）
    options: cmd.options ? cmd.options.map(o => ({ ...o })) : undefined,
    valueUtil: cmd.valueUtil || '',
    changeType: cmd.changeType || ((cmd.displayType === 'command' || cmd.displayType === 'select' || cmd.displayType === 'command_stateless') ? 'text' : 'int'),
    changeStep: cmd.changeStep != null ? cmd.changeStep : (cmd.changeType === 'float' ? Math.pow(10, -(cmd.changeNumberDecimal || 0)) : 1),
    changeNumberMin: cmd.changeNumberMin != null ? cmd.changeNumberMin : 0,
    changeNumberMax: cmd.changeNumberMax != null ? cmd.changeNumberMax : 100,
    changeNumberDecimal: cmd.changeNumberDecimal != null ? cmd.changeNumberDecimal : 0,
  }
}

/**
 * 组装控制页 card：DM row + ASM snapshot 设备行 → { uid, name, online, logoType, commands[] }。
 * card 标题用 snapshot 中文名（DM title 是 DM 侧配置名，非 ASM entry 名，设计要求不用）。
 * snapshot 行缺席（设备未建/未绑定）时回退 DM title，数值类回退 DM value。
 * @param {object} dmRow DM rows 元素
 * @param {object|null} snapDev snapshot 设备行 {logicDeviceUniqueId, displayName, online, attrs[]}
 */
export function buildCard(dmRow, snapDev) {
  const snapAttrs = new Map((snapDev && snapDev.attrs || []).map(a => [a.attrId, a]))
  return {
    uid: dmRow.deviceId,
    name: (snapDev && snapDev.displayName) || dmRow.title || dmRow.deviceId,
    online: snapDev ? snapDev.online !== false : dmRow.online !== false,
    logoType: dmRow.logoType || 'device',
    commands: (dmRow.commandList || []).map(normalizeCommand).map(cmd => {
      const snap = snapAttrs.get(cmd.attributeId)
      if (!snap) return cmd
      // 数值类：snapshot 实时值+单位（custom 出口）优先；枚举/文本类保持 DM key（SSE label 反查合并）
      if (snap.value != null && cmd.displayType === 'value_change') {
        return { ...cmd, value: String(snap.value), liveUnit: snap.unit || cmd.valueUtil }
      }
      if (cmd.displayType === 'value' && snap.valueText != null) {
        return { ...cmd, value: snap.valueText, liveUnit: snap.unit || cmd.valueUtil }
      }
      return cmd
    }),
  }
}

/** 组装全部控制页 card（DM rows + snapshot 设备行 map + 设备模块排序），按设备模块注册表序渲染。 */
export function buildCards(dmRows, snapshotDevices) {
  const snapMap = new Map((snapshotDevices || []).map(d => [d.logicDeviceUniqueId, d]))
  const cards = dmRows.map(row => buildCard(row, snapMap.get(row.deviceId)))
  const rank = uid => DEVICE_MODULES.findIndex(m => m.matches(uid))
  return cards.sort((a, b) => {
    const ra = rank(a.uid), rb = rank(b.uid)
    return (ra < 0 ? 99 : ra) - (rb < 0 ? 99 : rb)
  }).map(c => ({ ...c, commands: orderCommands(c.commands, c.uid) }))
}
