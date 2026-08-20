// 智能采样管（uid logicdevice_station.sampling_tube）：设定温度可改（步进 5℃），实际温度只读参照置前。
import { typeOfUid } from '../constants'

export default {
  typeKey: 'sampling_tube',
  matches(uid) { return typeOfUid(uid) === 'sampling_tube' },
  attrOrder: ['heating_set_temp', 'heating_actual_temp'],
  layout: 'grid2',
}
