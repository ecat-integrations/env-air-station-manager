// 空调（ac1/ac2，uid logicdevice_station.air_conditioner.ac1|ac2）显示定制：
// 控件项序 = 温度调节置顶（最常用），其余电源/风量/模式按 DM 配置序。
import { typeOfUid } from '../constants'

export default {
  typeKey: 'air_conditioner',
  matches(uid) { return typeOfUid(uid) === 'air_conditioner' },
  attrOrder: ['setpoint_temp', 'power_status', 'fan_speed', 'running_mode'],
  layout: 'grid2',   // 双列（温度调节独占一行，其余三项两列）
}
