import { normalizeComm, pickProtocolDeclarations } from './normalizeComm.js'

/**
 * 从 entry.data 识别连接类型（移植自 ADM config-display/resolveConnectionType.js）。
 * 先归一化字段名（各集成交错命名），再按「从站号 / 协议标识 / 地址形态」判定：
 *
 *   Modbus TCP ← ip_address（或 TCP 协议标识 / host+从站号）
 *   Modbus RTU ← 有从站号（或 Modbus 协议标识）+ 串口
 *   串口连接   ← 仅串口参数，无从站号、无 Modbus 标识（如私有 ASCII 协议）
 *   CLink/TCP  ← host+port，无从站号
 *
 * 连接拓扑（链路节点）与右侧「详细配置」都调这个函数 —— 同一台设备不可能在两处被判成不同类型。
 *
 * @returns {{ type: string, title: string, variant?: string, comm: object, serial: object, raw: object }}
 */
export function resolveConnectionType(entryData, hint = {}) {
  const raw = entryData && typeof entryData === 'object' ? entryData : {}
  // 兼容两种落盘：comm_settings 包一层，或串口/TCP 字段直接铺在顶层
  const commSource = (raw.comm_settings && typeof raw.comm_settings === 'object')
    ? raw.comm_settings
    : raw
  const { comm, serial } = normalizeComm(commSource)
  // comm_settings 内未声明协议时，回落到 entry.data 顶层的协议声明。
  const declared = pickProtocolDeclarations(raw)
  for (const [k, v] of Object.entries(declared)) {
    if (!(k in comm)) comm[k] = v
  }

  const hasSerial = serial.serial_port != null
  const hasSlave = comm.slave_id != null && comm.slave_id !== ''
  const modbusProto = String(comm.modbus_protocol || '').toUpperCase()
  const frameFormat = String(comm.frame_format || '').toUpperCase()
  const deviceProto = String(comm.device_protocol || '').toUpperCase()
  const hasIp = comm.ip_address != null && comm.ip_address !== ''
  const hasHost = comm.host != null && comm.host !== '' && comm.port != null
  const isThermo = String(hint.coordinate || '').includes('thermofisher')

  const base = { comm, raw }

  // ① Modbus TCP：有 IP，或显式 TCP 协议，或「host + 从站号」
  if (modbusProto === 'TCP' || hasIp || (hasHost && hasSlave)) {
    return { ...base, type: 'modbus-tcp', title: 'Modbus TCP', variant: null, serial: {} }
  }

  // ② Modbus 串行：显式 RTU/MODBUS，或有从站号（从站号是 Modbus 的强特征）
  if (modbusProto === 'RTU' || deviceProto === 'MODBUS' || hasSlave) {
    const isAscii = modbusProto === 'ASCII' || frameFormat === 'ASCII' || frameFormat === 'MODBUS ASCII'
    return {
      ...base,
      type: isAscii ? 'modbus-ascii' : 'modbus-rtu',
      title: isAscii ? 'Modbus ASCII' : 'Modbus RTU',
      variant: null,
      serial,
    }
  }

  // ③ TCP / CLink：host+port 且无从站号
  if (hasHost) {
    return {
      ...base,
      type: 'clink-tcp',
      title: isThermo ? 'CLink (TCP)' : 'TCP 连接',
      variant: isThermo ? 'clink' : 'tcp',
      serial: {},
    }
  }

  // ④ 串口（无从站号、非 Modbus）：私有协议 / ASCII
  if (hasSerial || Object.keys(serial).length) {
    const ascii = deviceProto === 'ASCII'
    return {
      ...base,
      type: 'serial',
      title: ascii ? 'ASCII 串口' : '串口连接',
      variant: ascii ? 'ascii' : 'serial',
      serial,
    }
  }

  // ⑤ 兜底
  return { ...base, type: 'unknown', title: '设备配置', variant: null, serial: {} }
}
