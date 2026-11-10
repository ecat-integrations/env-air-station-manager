import SerialDisplay from './templates/SerialDisplay.vue'
import ModbusRtuDisplay from './templates/ModbusRtuDisplay.vue'
import ModbusTcpDisplay from './templates/ModbusTcpDisplay.vue'
import ClinkTcpDisplay from './templates/ClinkTcpDisplay.vue'
import FallbackDisplay from './templates/FallbackDisplay.vue'

/** 连接类型 → 展示模板；未知走 FallbackDisplay（与 ADM registry 同构） */
const BY_TYPE = {
  serial: SerialDisplay,
  'modbus-rtu': ModbusRtuDisplay,
  'modbus-ascii': ModbusRtuDisplay,
  'modbus-tcp': ModbusTcpDisplay,
  'clink-tcp': ClinkTcpDisplay,
  unknown: FallbackDisplay,
}

export function templateFor(type) {
  return BY_TYPE[type] || FallbackDisplay
}
