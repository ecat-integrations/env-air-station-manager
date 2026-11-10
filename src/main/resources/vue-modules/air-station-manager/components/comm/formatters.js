/**
 * 连接摘要与字段行格式化（移植自 ADM `config-display/shared/formatters.js`）。
 *
 * 拓扑链路节点（主/次行）与右侧「详细配置」（摘要 + KV 行）共用这一份规则：
 * 同一条链路在两个地方必须显示一样，改这里两边同时生效。
 */
import { labelOf } from './labels.js'

const PARITY_ZH = {
  None: '无', N: '无', none: '无',
  Even: '偶', E: '偶', even: '偶',
  Odd: '奇', O: '奇', odd: '奇',
  Mark: '标记', Space: '空格',
}

const FLOW_ZH = {
  None: '无', none: '无', '0': '无',
}

const TCP_PROTOCOL_ZH = {
  TCP: 'Modbus TCP (MBAP帧)',
  RTU_OVER_TCP: 'Modbus RTU over TCP',
}

const DEVICE_PROTOCOL_ZH = {
  MODBUS: 'Modbus',
  ASCII: 'ASCII 串口',
}

const MODBUS_PROTOCOL_ZH = {
  // 不标 RS485：entry.data 无法区分 RS232/RS485，且 Modbus RTU 可跑在两种接口上
  RTU: 'Modbus RTU',
  ASCII: 'Modbus ASCII',
  TCP: 'Modbus TCP',
}

/** PM 通道布局（天虹等：决定从站号默认值与是否解析双通道） */
const PM_LAYOUT_ZH = {
  pm2_5: 'PM2.5',
  pm10: 'PM10',
  dual: 'PM2.5 + PM10（双通道）',
}

export function formatValue(key, value) {
  if (value == null || value === '') return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  const s = String(value)
  if (key === 'parity') return PARITY_ZH[s] || s
  if (key === 'flow_control') return FLOW_ZH[s] || s
  if (key === 'tcp_protocol') return TCP_PROTOCOL_ZH[s] || s
  if (key === 'device_protocol') return DEVICE_PROTOCOL_ZH[s] || s
  if (key === 'modbus_protocol') return MODBUS_PROTOCOL_ZH[s] || s
  if (key === 'pm_layout') return PM_LAYOUT_ZH[s] || s
  return s
}

/** 串口帧格式校验位：N/E/O（Odd 的 O）。摘要行用非等宽加粗字，避免 O 被看成 0。 */
function parityMark(parityRaw) {
  const s = String(parityRaw ?? 'None')
  return ({ None: 'N', Even: 'E', Odd: 'O', N: 'N', E: 'E', O: 'O' })[s]
    || s.charAt(0).toUpperCase()
}

/** 串口一行摘要：COM3 · 9600 8N1 */
export function serialSummary(serial = {}) {
  if (!serial || typeof serial !== 'object') return ''
  const port = serial.serial_port
  const baud = serial.baudrate
  const data = serial.data_bits ?? '8'
  const stop = serial.stop_bits ?? '1'
  const mark = parityMark(serial.parity)
  const parts = []
  if (port) parts.push(String(port))
  if (baud) parts.push(`${baud} ${data}${mark}${stop}`)
  return parts.join(' · ')
}

export function tcpSummary(host, port) {
  if (!host && (port == null || port === '')) return ''
  if (host && port != null && port !== '') return `${host}:${port}`
  return host ? String(host) : `端口 ${port}`
}

/** 按已解析连接类型生成一行摘要（拓扑链路节点 / 详细配置摘要共用） */
export function connectionSummary(resolved) {
  if (!resolved) return ''
  const comm = resolved.comm || {}
  switch (resolved.type) {
    case 'serial':
      return serialSummary(resolved.serial)
    case 'modbus-rtu':
    case 'modbus-ascii': {
      const base = serialSummary(resolved.serial)
      const slave = comm.slave_id
      if (slave == null || slave === '') return base
      const slavePart = `从站 ${slave}`
      return base ? `${base} · ${slavePart}` : slavePart
    }
    case 'modbus-tcp': {
      const addr = tcpSummary(comm.ip_address, comm.port)
      const slave = comm.slave_id
      if (slave == null || slave === '') return addr
      return addr ? `${addr} · 从站 ${slave}` : `从站 ${slave}`
    }
    case 'clink-tcp':
      return tcpSummary(comm.host, comm.port)
    default:
      return ''
  }
}

/** 将对象压成 { key, label, value } 行；跳过对象/数组嵌套（由调用方展开） */
export function toFieldRows(obj, { keys, skipKeys } = {}) {
  if (!obj || typeof obj !== 'object') return []
  const skip = skipKeys || new Set()
  const list = keys || Object.keys(obj)
  const rows = []
  for (const key of list) {
    if (skip.has(key)) continue
    if (!(key in obj)) continue
    const val = obj[key]
    if (val != null && typeof val === 'object') continue
    rows.push({ key, label: labelOf(key), value: formatValue(key, val) })
  }
  return rows
}

/** 便捷包装：整份对象直接转 KV 行（不需要指定顺序时的兜底展示）。 */
export function labelOfRows(obj) {
  return toFieldRows(obj)
}

export const SERIAL_FIELD_ORDER = [
  'serial_port', 'baudrate', 'data_bits', 'stop_bits', 'parity', 'flow_control', 'timeout',
]
