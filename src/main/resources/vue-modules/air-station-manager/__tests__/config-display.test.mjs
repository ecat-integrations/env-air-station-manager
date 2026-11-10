// 详细配置展示（连接识别 / KV 释义 / 其他配置取数）单测（无 jest；node 原生 assert 直跑）。
//   node __tests__/config-display.test.mjs
//
// 关注点：
//   · 四种连接类型的识别边界（串口 / Modbus RTU / Modbus TCP / CLink-TCP / 未知）
//   · 「共性连接类型」键集合是唯一来源：连接分项与其他配置不重不漏
//   · KV 中文释义与枚举值翻译（校验位 无/奇/偶、Modbus RTU/TCP…）
//   · 其他配置跳过身份字段、流程元数据、表单提示语，保留 sn / 采集间隔
import assert from 'node:assert/strict'
import {
  CONNECTION_KEYS, canonicalKey, normalizeComm, pickProtocolDeclarations,
} from '../components/comm/normalizeComm.js'
import { resolveConnectionType } from '../components/comm/resolveConnectionType.js'
import {
  connectionSummary, formatValue, labelOfRows, toFieldRows,
} from '../components/comm/formatters.js'
import { FIELD_LABELS } from '../components/comm/labels.js'
import { extractExtraRows } from '../components/config-display/shared/extraConfig.js'

let passed = 0
let failed = 0
function test(name, fn) {
  try {
    fn()
    passed++
    console.log('  ok - ' + name)
  } catch (e) {
    failed++
    console.error('  FAIL - ' + name + ': ' + e.message)
  }
}

/* ================= 键归一化 ================= */
test('交错命名收敛到规范键（驼峰 / 别名 / 嵌套串口块）', () => {
  const { comm, serial } = normalizeComm({
    serialPort: 'COM3',
    baudRate: '9600',
    numDataBit: 8,
    numStopBit: 1,
    parityBit: 'None',
    slaveId: 6,
    serial_settings: { timeoutMs: 500 },
  })
  assert.equal(comm.serial_port, 'COM3')
  assert.equal(comm.baudrate, '9600')
  assert.equal(comm.data_bits, 8)
  assert.equal(comm.stop_bits, 1)
  assert.equal(comm.parity, 'None')
  assert.equal(comm.slave_id, 6)
  assert.equal(comm.timeout, 500)
  assert.deepEqual(Object.keys(serial).sort(), [
    'baudrate', 'data_bits', 'parity', 'serial_port', 'stop_bits', 'timeout',
  ].sort())
})

test('port 一词双义：有 IP 时归 TCP 端口，否则归串口号', () => {
  assert.equal(normalizeComm({ ip_address: '10.0.0.5', port: 502 }).comm.port, 502)
  assert.equal(normalizeComm({ port: 'COM9' }).comm.serial_port, 'COM9')
})

test('身份/流程元数据不进连接键', () => {
  const { comm } = normalizeComm({ name: 'x', model: 'y', vendor: 'z', flow_type: 'f', serial_port: 'COM1' })
  assert.deepEqual(Object.keys(comm), ['serial_port'])
})

test('顶层协议声明只认三个键', () => {
  const declared = pickProtocolDeclarations({
    device_protocol: 'MODBUS', modbus_protocol: 'RTU', frame_format: 'ASCII',
    address: '0x01', remark: 'x',
  })
  assert.deepEqual(Object.keys(declared).sort(), ['device_protocol', 'frame_format', 'modbus_protocol'])
})

test('canonicalKey 暴露 port 双义（供其他配置排除）', () => {
  assert.equal(canonicalKey('ipAddress'), 'ip_address')
  assert.equal(canonicalKey('port'), 'port')
  assert.equal(canonicalKey('未知键'), null)
})

/* ================= 连接类型识别 ================= */
test('仅串口参数 → serial', () => {
  const r = resolveConnectionType({ comm_settings: { serial_port: 'COM22', baudrate: 9600 } })
  assert.equal(r.type, 'serial')
  assert.equal(r.title, '串口连接')
})

test('有从站号 → Modbus RTU', () => {
  const r = resolveConnectionType({ comm_settings: { serial_port: 'COM3', baudrate: 9600, slave_id: 6 } })
  assert.equal(r.type, 'modbus-rtu')
  assert.equal(r.title, 'Modbus RTU')
})

test('帧格式 ASCII → Modbus ASCII', () => {
  const r = resolveConnectionType({ comm_settings: { serial_port: 'COM3', frame_format: 'ASCII', slave_id: 1 } })
  assert.equal(r.type, 'modbus-ascii')
})

test('有 IP → Modbus TCP（即便同时写了串口）', () => {
  const r = resolveConnectionType({ comm_settings: { ip_address: '192.168.1.60', port: 502, slave_id: 1 } })
  assert.equal(r.type, 'modbus-tcp')
})

test('host + port 且无从站号 → TCP 连接；thermofisher 认 CLinK', () => {
  assert.equal(resolveConnectionType({ comm_settings: { host: '10.1.1.2', port: 8000 } }).type, 'clink-tcp')
  const thermo = resolveConnectionType(
    { comm_settings: { host: '10.1.1.2', port: 9883 } },
    { coordinate: 'com.ecat:integration-thermofisher' },
  )
  assert.equal(thermo.title, 'CLink (TCP)')
})

test('comm_settings 缺失时读顶层扁平字段', () => {
  const r = resolveConnectionType({ serialPort: 'COM7', slavId: 2, slave_id: 2 })
  assert.equal(r.comm.serial_port, 'COM7')
  assert.equal(r.type, 'modbus-rtu')
})

test('顶层协议声明补进连接块（comm_settings 未声明时）', () => {
  const r = resolveConnectionType({
    comm_settings: { serial_port: 'COM5' },
    device_protocol: 'MODBUS',
  })
  assert.equal(r.type, 'modbus-rtu')
})

test('无任何线索 → unknown（不猜）', () => {
  const r = resolveConnectionType({ bus_type_label: '请选择总线类型' })
  assert.equal(r.type, 'unknown')
  assert.equal(connectionSummary(r), '')
})

/* ================= 摘要 ================= */
test('摘要按类型生成：串口 / RTU 带从站 / TCP 带 IP / CLink 带 host', () => {
  assert.equal(connectionSummary(resolveConnectionType({
    comm_settings: { serial_port: 'COM22', baudrate: 9600, data_bits: 8, stop_bits: 1, parity: 'None' },
  })), 'COM22 · 9600 8N1')
  assert.equal(connectionSummary(resolveConnectionType({
    comm_settings: { serial_port: 'COM3', baudrate: 19200, data_bits: 8, stop_bits: 1, parity: 'Odd', slave_id: 6 },
  })), 'COM3 · 19200 8O1 · 从站 6')
  assert.equal(connectionSummary(resolveConnectionType({
    comm_settings: { ip_address: '192.168.1.60', port: 502, slave_id: 1 },
  })), '192.168.1.60:502 · 从站 1')
  assert.equal(connectionSummary(resolveConnectionType({
    comm_settings: { host: '10.1.1.2', port: 8000 },
  })), '10.1.1.2:8000')
})

/* ================= KV 释义 ================= */
test('字段中文释义覆盖连接参数（含串口/Modbus/TCP 三组）', () => {
  const connLabels = [
    'serial_port', 'baudrate', 'data_bits', 'stop_bits', 'parity', 'flow_control',
    'slave_id', 'modbus_protocol', 'frame_format', 'ip_address', 'port', 'host',
  ]
  for (const k of connLabels) {
    assert.ok(FIELD_LABELS[k], '缺释义：' + k)
    assert.notEqual(FIELD_LABELS[k], k)
  }
})

test('枚举值翻译：校验位 / 流控 / 协议', () => {
  assert.equal(formatValue('parity', 'None'), '无')
  assert.equal(formatValue('parity', 'Even'), '偶')
  assert.equal(formatValue('parity', 'Odd'), '奇')
  assert.equal(formatValue('flow_control', '0'), '无')
  assert.equal(formatValue('modbus_protocol', 'RTU'), 'Modbus RTU')
  assert.equal(formatValue('tcp_protocol', 'TCP'), 'Modbus TCP (MBAP帧)')
  assert.equal(formatValue('device_protocol', 'ASCII'), 'ASCII 串口')
  assert.equal(formatValue('sn', ''), '—')
  assert.equal(formatValue('confirmed', true), '是')
})

test('toFieldRows 只出标量、带 label 与释义、可按 keys 定序', () => {
  const rows = toFieldRows(
    { serial_port: 'COM3', parity: 'Odd', nested: { a: 1 }, extra: 'x' },
    { keys: ['serial_port', 'parity', 'nested'] },
  )
  assert.deepEqual(rows.map(r => r.key), ['serial_port', 'parity'])
  assert.deepEqual(rows.map(r => r.label), ['串口', '校验位'])
  assert.deepEqual(rows.map(r => r.value), ['COM3', '奇'])
})

/* ================= 其他配置取数 ================= */
test('其他配置保留 sn / 采集间隔，跳过身份与流程元数据', () => {
  const rows = extractExtraRows({
    id: 'x', name: '质控仪', model: 'SMS8910V2', vendor: 'saimosen', class: 'air.monitor.qc',
    coordinate: 'com.ecat:integration-saimosen', title: '质控仪',
    flow_type: 'saimosen-device-config', created_at: '2026-10-16',
    confirmed: 'true', configSummary: 'yaml…',
    sn: 'qcv2', poll_interval_sec: 5,
  })
  assert.deepEqual(rows.map(r => r.key), ['sn', 'poll_interval_sec'])
  assert.deepEqual(rows.map(r => r.label), ['序列号', '采集间隔(秒)'])
})

test('其他配置跳过连接参数（不重不漏：与 CONNECTION_KEYS 同一来源）', () => {
  const rows = extractExtraRows({
    serial_port: 'COM3', baudrate: 9600, slave_id: 1, ip_address: '10.0.0.1', port: 502,
    host: 'h', timeout: 500, modbus_protocol: 'RTU',
    sn: 'SN1',
  })
  assert.deepEqual(rows.map(r => r.key), ['sn'])
})

test('其他配置跳过表单提示语（*_label / *_tip / 整句中文说明）与嵌套结构', () => {
  const rows = extractExtraRows({
    class_type_label: '请选择设备类型。支持校准器、质控仪、分析仪等多种 Saimosen 环境监测设备。',
    protocol_tip: '按设备手册选择',
    _internal: 'x',
    serial_settings: { serial_port: 'COM3' },
    // 命名不规范的提示字段兜底：长句 + 中文句号
    note: '这是一段很长的说明文字，用于提示用户该选哪一项，通常来自配置流的只读文本框。',
    poll_interval_sec: 10,
  })
  assert.deepEqual(rows.map(r => r.key), ['poll_interval_sec'])
})

test('其他配置空对象不产生行', () => {
  assert.deepEqual(extractExtraRows({}), [])
  assert.deepEqual(extractExtraRows(null), [])
})

test('CONNECTION_KEYS 覆盖所有可识别的连接规范键', () => {
  const conn = normalizeComm({
    serialPort: 'COM1', baudRate: 9600, dataBit: 8, stopBit: 1, parity: 'None',
    flowControl: '0', slaveId: 1, modbusProtocol: 'RTU', tcpProtocol: 'TCP',
    deviceProtocol: 'MODBUS', frameFormat: 'RTU', ipAddress: '1.1.1.1', host: 'h',
    connTimeout: 3, readTimeout: 3, stationId: 1, channel: 2, timeout: 100,
  }).comm
  for (const key of Object.keys(conn)) {
    if (key === 'port') continue
    assert.ok(CONNECTION_KEYS.has(key), '连接键未纳入 CONNECTION_KEYS：' + key)
  }
})

// labelOfRows 只是给模板复用的便捷包装：此处确认它不改变行结构
test('labelOfRows 包装后结构不变', () => {
  const rows = labelOfRows({ sn: 'X' })
  assert.deepEqual(rows, [{ key: 'sn', label: '序列号', value: 'X' }])
})

console.log(`\n${passed} passed, ${failed} failed`)
if (failed) process.exit(1)
