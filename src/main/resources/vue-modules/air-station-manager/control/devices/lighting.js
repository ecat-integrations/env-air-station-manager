// 室内灯光（uid logicdevice_station.lighting）：单开关项，紧凑布局。
import { typeOfUid } from '../constants'

export default {
  typeKey: 'lighting',
  matches(uid) { return typeOfUid(uid) === 'lighting' },
  attrOrder: ['switch_status'],
  layout: 'rows',
}
