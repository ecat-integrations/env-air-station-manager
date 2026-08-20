// 门禁（uid logicdevice_station.access_control）：door_control 无状态按钮（开门/关门）+ lock_status 只读态
// （只读态置后，操作优先）。
import { typeOfUid } from '../constants'

export default {
  typeKey: 'access_control',
  matches(uid) { return typeOfUid(uid) === 'access_control' },
  attrOrder: ['door_control', 'lock_status'],
  layout: 'rows',
}
