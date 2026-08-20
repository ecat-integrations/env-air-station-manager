// 智能稳压电源（uid logicdevice_station.voltage_regulator）：4 路继电器开关，双列排布。
import { typeOfUid } from '../constants'

export default {
  typeKey: 'voltage_regulator',
  matches(uid) { return typeOfUid(uid) === 'voltage_regulator' },
  attrOrder: ['relay_ch1', 'relay_ch2', 'relay_ch3', 'relay_ch4'],
  layout: 'grid2',
}
