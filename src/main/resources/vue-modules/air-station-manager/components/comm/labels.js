/**
 * 字段中文释义：对齐各共享 ConfigSchema 的 displayName（移植自 ADM config-display/shared/labels.js）。
 * 来源：SerialCommConfigSchema / ModbusRtu·TcpCommConfigSchema /
 *       TcpClientCommConfigSchema / ThermoFisher TCP-CLink 表单。
 *
 * 拓扑链路节点与右侧「详细配置」都从这里取释义，禁止各自维护一份键名表。
 */
export const FIELD_LABELS = {
  // SerialCommConfigSchema
  serial_port: '串口',
  baudrate: '波特率',
  data_bits: '数据位',
  stop_bits: '停止位',
  parity: '校验位',
  flow_control: '流控',
  timeout: '超时时间(ms)',

  // Modbus
  slave_id: '从站 ID',
  modbus_protocol: 'Modbus 协议',
  frame_format: '帧格式',
  tcp_protocol: 'TCP 协议模式',
  ip_address: 'IP 地址',
  port: '端口',

  // TCP / CLink
  host: '目标地址',
  conn_timeout: '连接超时(秒)',
  read_timeout: '读取超时(秒)',

  // 串口嵌套块 / 业务扩展
  serial_settings: '串口设置',
  station_id: '站点号',
  channel: '通道号',
  device_protocol: '通讯协议',

  // 设备身份（兜底 KV 用；主身份卡已展示时可跳过）
  name: '设备名称',
  sn: '序列号',
  vendor: '厂商',
  model: '型号',
  class: '设备类型',
  poll_interval_sec: '采集间隔(秒)',
  pm_layout: 'PM 布局',
}

/** 连接展示时跳过的顶层键（身份卡或内部结构已覆盖） */
export const SKIP_TOP_KEYS = new Set([
  'id', 'name', 'sn', 'vendor', 'model', 'class', 'title',
  'coordinate', 'unique_id', 'uniqueId',
])

export function labelOf(key) {
  return FIELD_LABELS[key] || key
}
