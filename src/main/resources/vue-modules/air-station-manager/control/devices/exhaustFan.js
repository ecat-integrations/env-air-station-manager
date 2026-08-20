// 排风扇（uid logicdevice_station.exhaust_fan）：speed 四档按钮组（off/low/medium/high），单行。
import { typeOfUid } from '../constants'

export default {
  typeKey: 'exhaust_fan',
  matches(uid) { return typeOfUid(uid) === 'exhaust_fan' },
  attrOrder: ['speed'],
  layout: 'rows',
}
