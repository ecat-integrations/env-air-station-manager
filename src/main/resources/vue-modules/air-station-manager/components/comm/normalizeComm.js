/**
 * comm_settings 字段名归一化（schema 字段命名各集成交错：冒号/驼峰/下划线/别名）。
 *
 * 移植自 ADM `components/config-display/normalizeComm.js`（同一份规则，避免两份实现漂移）。
 * 背景：不同集成写 entry.data 的键长成各种样子——
 *   saimosen: serialPort / baudRate / dataBit / stopBit / parity / slaveId
 *   tianhong: port / baudRate / numDataBit / numStopBit / parity / slaveId
 *   thermo:   host / port / conn_timeout / read_timeout
 *   modbus:   ip_address / port / slave_id / tcp_protocol
 * 只靠固定键名判断连接类型必然漏判，故先把键收敛到一套规范键再识别。
 */

/** 归一化键（去掉非字母数字并转小写）→ 规范键 */
const KEY_ALIASES = {
  // 串口
  serialport: 'serial_port',
  portname: 'serial_port',
  comport: 'serial_port',
  // 波特率
  baudrate: 'baudrate',
  baud: 'baudrate',
  // 数据位
  databit: 'data_bits',
  databits: 'data_bits',
  numdatabit: 'data_bits',
  numdatabits: 'data_bits',
  // 停止位
  stopbit: 'stop_bits',
  stopbits: 'stop_bits',
  numstopbit: 'stop_bits',
  numstopbits: 'stop_bits',
  // 校验位 / 流控 / 超时
  parity: 'parity',
  paritybit: 'parity',
  flowcontrol: 'flow_control',
  timeout: 'timeout',
  timeoutms: 'timeout',
  serialtimeout: 'timeout',
  // Modbus 从站与协议
  slaveid: 'slave_id',
  slaveaddr: 'slave_id',
  slaveaddress: 'slave_id',
  stationid: 'station_id',
  station: 'station_id',
  modbusprotocol: 'modbus_protocol',
  modbusmode: 'modbus_protocol',
  // 串行帧格式（RTU/ASCII）：平台目前只实现 RTU，但保留显式字段以便将来识别
  framemode: 'frame_format',
  frameformat: 'frame_format',
  tcpprotocol: 'tcp_protocol',
  deviceprotocol: 'device_protocol',
  protocol: 'device_protocol',
  // TCP / CLink
  ip: 'ip_address',
  ipaddress: 'ip_address',
  ipaddr: 'ip_address',
  host: 'host',
  hostname: 'host',
  targetaddress: 'host',
  address: 'host',
  conntimeout: 'conn_timeout',
  connecttimeout: 'conn_timeout',
  readtimeout: 'read_timeout',
  channel: 'channel',
}

/** 嵌套串口块（不同集成叫法不一），展开后并入同一层 */
const SERIAL_NESTED = new Set([
  'serialsettings', 'serialconfig', 'serialcomm', 'serial',
])

/** 这些键是身份/流程元数据，不进连接配置 */
const NOISE = new Set([
  'flowtype', 'createdat', 'updatedat', 'id', 'entryid', 'deviceid',
  'uniqueid', 'coordinate', 'title', 'pmlayout', 'name', 'vendor',
  'model', 'class', 'sn',
])

/** 串口字段展示顺序（详情面板与摘要共用同一顺序）。 */
export const SERIAL_FIELD_ORDER = [
  'serial_port', 'baudrate', 'data_bits', 'stop_bits', 'parity', 'flow_control', 'timeout',
]

/**
 * 「共性连接类型」键集合：连接模板负责渲染的规范键。
 *
 * <p>详情面板用它把「连接参数」与「其他部署参数」分开；「其他配置」区块用它做排除，
 * 两边共用同一份清单——新增一种连接参数只改这里，不会出现「详情重复展示」或「漏展示」。
 */
export const CONNECTION_KEYS = new Set([
  ...SERIAL_FIELD_ORDER,
  'slave_id', 'modbus_protocol', 'tcp_protocol', 'device_protocol', 'frame_format',
  'ip_address', 'host', 'port', 'station_id', 'channel',
  'conn_timeout', 'read_timeout',
])

function normalizeKey(key) {
  return String(key || '').toLowerCase().replace(/[^a-z0-9]/g, '')
}

/**
 * 单个键 → 规范键（未知键返回 null）。供「判断某顶层键是否属于连接参数」复用。
 * port 单独返回 'port'：它双义（串口号 / TCP 端口），归一化阶段需按上下文再判定。
 */
export function canonicalKey(key) {
  const nk = normalizeKey(key)
  if (nk === 'port') return 'port'
  return KEY_ALIASES[nk] || null
}

function isNumeric(v) {
  if (typeof v === 'number') return Number.isFinite(v)
  return typeof v === 'string' && v.trim() !== '' && /^\d+$/.test(v.trim())
}

/**
 * 把任意形状的 comm_settings 收敛成规范键。
 * @returns {{ comm: object, serial: object }} comm 含全部规范键；serial 仅串口块
 */
export function normalizeComm(source) {
  const comm = {}
  const portCandidates = []
  const put = (key, value) => {
    if (value == null || value === '') return
    if (!(key in comm)) comm[key] = value
  }

  const visit = (obj) => {
    if (!obj || typeof obj !== 'object') return
    for (const [k, v] of Object.entries(obj)) {
      const nk = normalizeKey(k)
      if (!nk) continue
      // 嵌套串口块：递归并入
      if (SERIAL_NESTED.has(nk) && v && typeof v === 'object') {
        visit(v)
        continue
      }
      // port 一词双义（串口号 vs TCP 端口），先收集，末尾按上下文判定
      if (nk === 'port') {
        if (v != null && v !== '') portCandidates.push(v)
        continue
      }
      const canon = KEY_ALIASES[nk]
      if (canon) {
        put(canon, v)
        continue
      }
      if (NOISE.has(nk)) continue
      // 未知但扁平的键保留原样（模板不认，兜底展示可读）
      if (v == null || typeof v === 'object') continue
      put(k, v)
    }
  }

  visit(source)

  const hasTcp = ('ip_address' in comm) || ('host' in comm)
  for (const v of portCandidates) {
    if (hasTcp && isNumeric(v)) put('port', v)
    else put('serial_port', v)
  }

  const serial = {}
  for (const f of SERIAL_FIELD_ORDER) {
    if (f in comm) serial[f] = comm[f]
  }
  return { comm, serial }
}

/** 顶层「协议声明」类键（各集成常写在 entry.data 顶层而非 comm_settings 内） */
const DECLARATION_KEYS = new Set(['device_protocol', 'modbus_protocol', 'frame_format'])

/**
 * 从 entry.data 顶层摘出协议声明（device_protocol / modbus_protocol / frame_format）。
 * 只认这三个键，避免把顶层业务字段（如 address、station 之类）误当连接参数。
 * @returns {object} 规范键 → 值；无声明时返回 {}
 */
export function pickProtocolDeclarations(source) {
  const out = {}
  if (!source || typeof source !== 'object') return out
  for (const [k, v] of Object.entries(source)) {
    if (v == null || v === '' || typeof v === 'object') continue
    const canon = KEY_ALIASES[normalizeKey(k)]
    if (!canon || !DECLARATION_KEYS.has(canon)) continue
    if (!(canon in out)) out[canon] = v
  }
  return out
}
