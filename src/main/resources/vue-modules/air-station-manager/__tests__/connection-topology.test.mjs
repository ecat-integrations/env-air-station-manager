// 站房设备「连接拓扑」几何 / 摘要 / 图标映射单测（无 jest；node 原生 assert 直跑）。
//   node __tests__/connection-topology.test.mjs
//
// 关注点（全部是纯数据断言，不碰 DOM）：
//   · 类型槽分组卡按 StationParamMeta 顺序成 6 组，计数正确
//   · 一台设备只出现一个节点（多槽对一 = 总线汇聚，不是重复画设备）
//   · y 坐标由行序推出 → 选中态不改变任何坐标与顺序
//   · 设备纵向避让不重叠；跨组设备被标记
//   · 链路摘要 l1/l2 拆分（串口 / Modbus / TCP / 未知）
//   · 每一类站房设备都能映射到一个专属图标（不允许大面积落到 generic）
//   · 列宽分配在窄容器下优先保设备卡不被裁切
import assert from 'node:assert/strict'
import {
  buildTopology, columnGeometry, describeLink, countCrossings, median, LAYOUT,
} from '../components/connection-topology/buildTopology.js'
import { curvePath, hopPath } from '../components/connection-topology/edgePath.js'
import { glyphKindOf, slotKindKey } from '../components/connection-topology/glyphs/glyphKind.js'
import { STATION_GLYPHS, GLYPH_KINDS } from '../components/connection-topology/glyphs/stationGlyphDefs.js'
import { typeOf } from '../stationParamMeta.js'

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

/** 构造 ParamBinding（只填拓扑用得到的字段） */
function bind(param, extra = {}) {
  return {
    param,
    configured: !!extra.deviceId,
    state: extra.deviceId ? 'CONFIGURED' : 'NOT_CREATED',
    boundDeviceId: extra.deviceId || null,
    coordinate: extra.coordinate || '',
    title: extra.title || '',
    deviceModel: extra.model || '',
    deviceVendor: extra.vendor || '',
    deviceClass: extra.deviceClass || '',
  }
}

/** 37 槽（顺序 = StationParamMeta 声明顺序）。 */
const SLOTS = [
  'TH', 'POWER_METER', 'VOLTAGE_REGULATOR', 'UPS',
  'AIR_CONDITIONER_AC1', 'AIR_CONDITIONER_AC2', 'EXHAUST_FAN', 'LIGHTING',
  'ZERO_GAS_RELAY', 'SECURITY_ALARM', 'SAMPLING_TUBE',
  'STANDARD_GAS_SO2', 'STANDARD_GAS_CO', 'STANDARD_GAS_NOX',
  'FILTER_CHANGER_SO2', 'FILTER_CHANGER_CO', 'FILTER_CHANGER_O3', 'FILTER_CHANGER_NOX',
  'CALIBRATOR', 'ELECTRONIC_FENCE', 'ACCESS_CONTROL',
  'CAMERA_1', 'CAMERA_2', 'CAMERA_3', 'CAMERA_4',
  'CLEANLINESS', 'INDOOR_POLLUTANT',
  'PM_ZERO_CHECK_PM10', 'PM_ZERO_CHECK_PM25',
  'CUTTER_CHANGER_PM10', 'CUTTER_CHANGER_PM25',
  'PAPER_TAPE_PM10', 'PAPER_TAPE_PM25',
  'VALVE_GROUP_SO2', 'VALVE_GROUP_CO', 'VALVE_GROUP_NO', 'VALVE_GROUP_O3',
]

/** d-power 同时服务「供配电」的 POWER_METER 与「站房动环」的 LIGHTING → 跨组 */
const BINDINGS = SLOTS.map((slot) => {
  switch (slot) {
    case 'TH':
      return bind(slot, { deviceId: 'd-th', title: '站房温湿度', model: 'TH2000', vendor: 'saimosen', deviceClass: 'weather.sensor' })
    case 'POWER_METER':
    case 'LIGHTING':
      return bind(slot, { deviceId: 'd-power', title: '配电监测单元', model: 'PD-100', vendor: 'tongyang', deviceClass: 'power.sensor' })
    case 'AIR_CONDITIONER_AC1':
      return bind(slot, { deviceId: 'd-ac', title: '站房空调', model: 'AC-2000', vendor: 'tongyang', deviceClass: 'air.conditioner' })
    case 'CAMERA_1':
    case 'CAMERA_2':
      return bind(slot, { deviceId: 'd-cam', title: '站房摄像头', model: 'CAM-4K', vendor: 'hik', deviceClass: 'default.sensor' })
    case 'STANDARD_GAS_SO2':
      return bind(slot, { deviceId: 'd-gas', title: 'SO2 标气', model: 'GAS-1', vendor: 'saimosen', deviceClass: 'default.sensor' })
    default:
      return bind(slot)
  }
})

const BRIEFS = {
  'd-th': {
    coordinate: 'com.ecat:integration-saimosen',
    className: 'weather.sensor',
    commSettings: { serial_port: 'COM22', baudrate: '9600', data_bits: 8, stop_bits: 1, parity: 'None' },
  },
  'd-power': {
    coordinate: 'com.ecat:integration-tjtongyangkeji',
    className: 'power.sensor',
    commSettings: { serial_port: 'COM3', baudrate: '9600', data_bits: 8, stop_bits: 1, parity: 'None', slave_id: 6 },
  },
  'd-ac': {
    coordinate: 'com.ecat:integration-tjtongyangkeji',
    className: 'air.conditioner',
    commSettings: { ip_address: '192.168.1.60', port: 502, slave_id: 1 },
  },
  'd-cam': {
    coordinate: 'com.ecat:integration-camera',
    className: '',
    commSettings: { host: '192.168.1.70', port: 8000 },
  },
  'd-gas': {
    coordinate: 'com.ecat:integration-saimosen',
    className: 'default.sensor',
    commSettings: null,
  },
}

/* ================= 基础工具 ================= */
test('median 偶数取均值 / 奇数取中位', () => {
  assert.equal(median([]), 0)
  assert.equal(median([5]), 5)
  assert.equal(median([10, 30]), 20)
  assert.equal(median([30, 10, 20]), 20)
})

/* ================= 分组卡 ================= */
test('六组按 GROUP_ORDER 成组，共 37 槽', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  assert.deepEqual(t.cards.map(c => c.name), ['环境监测', '供配电', '站房动环', '采样与标气', '校准与耗材', '安防视频'])
  assert.equal(t.stats.total, 37)
  assert.equal(t.cards.reduce((n, c) => n + c.total, 0), 37)
})

test('分组卡纵向不重叠，间隙 = cardGapY', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  for (let i = 1; i < t.cards.length; i++) {
    const prev = t.cards[i - 1]
    assert.equal(t.cards[i].top, prev.top + prev.height + LAYOUT.cardGapY)
  }
})

test('组头 31px / 槽行 32px / 卡内上下各 4px', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const card = t.cards[0]
  assert.equal(card.top, LAYOUT.padY)
  assert.equal(card.height, LAYOUT.grpH + LAYOUT.cardPadTop + card.rows.length * LAYOUT.rowH + LAYOUT.cardPadY)
})

test('行中心 y = 卡顶 + 组头 + 卡顶内边距 + 行序 * 行高 + 半个行高（端点可纯数算）', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  for (const c of t.cards) {
    c.rows.forEach((r, i) => {
      assert.equal(r.y, c.top + LAYOUT.grpH + LAYOUT.cardPadTop + i * LAYOUT.rowH + LAYOUT.rowH / 2)
    })
  }
})

test('画布留白左右上下统一 18px', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const g = columnGeometry(1200)
  assert.equal(g.x1 - g.pcolW, LAYOUT.padX)
  assert.equal(g.devX + g.devW <= 1200 - LAYOUT.padX + 0.001, true)
  assert.equal(t.cards[0].top, LAYOUT.padY)
})

/* ================= 设备节点 ================= */
test('同一台设备只出现一个节点（多槽对一 = 总线汇聚）', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const ids = t.nodes.map(n => n.deviceId)
  assert.equal(new Set(ids).size, ids.length)
  const power = t.nodes.find(n => n.deviceId === 'd-power')
  assert.deepEqual(power.paramKeys.sort(), ['LIGHTING', 'POWER_METER'])
  const cam = t.nodes.find(n => n.deviceId === 'd-cam')
  assert.deepEqual(cam.paramKeys.sort(), ['CAMERA_1', 'CAMERA_2'])
})

test('跨组设备被打标（供配电 + 站房动环）', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const power = t.nodes.find(n => n.deviceId === 'd-power')
  assert.equal(power.crossGroup, true)
  assert.deepEqual(power.groupList.sort(), ['供配电', '站房动环'].sort())
  const th = t.nodes.find(n => n.deviceId === 'd-th')
  assert.equal(th.crossGroup, false)
})

test('设备卡纵向不重叠（间距 >= devGapY）', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  for (let i = 1; i < t.nodes.length; i++) {
    assert.ok(t.nodes[i].y - t.nodes[i - 1].y >= LAYOUT.devGapY - 0.001)
  }
})

test('单槽设备退化为直线（槽 y 与设备 y 相同）', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const th = t.nodes.find(n => n.deviceId === 'd-th')
  assert.equal(th.y, t.params.find(p => p.paramKey === 'TH').y)
})

test('选中态只改 active，不动坐标与顺序', () => {
  const a = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const b = buildTopology({ bindings: BINDINGS, briefs: BRIEFS, selectedParam: 'LIGHTING' })
  assert.deepEqual(a.nodes.map(n => [n.deviceId, n.y, n.kind]), b.nodes.map(n => [n.deviceId, n.y, n.kind]))
  assert.equal(b.params.find(p => p.paramKey === 'LIGHTING').active, true)
  assert.equal(b.nodes.find(n => n.deviceId === 'd-power').active, true)
})

test('未配置的槽没有 deviceId（不参与连线）', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const idle = t.params.filter(p => !p.configured)
  assert.ok(idle.length > 0)
  assert.ok(idle.every(p => p.deviceId === null))
})

test('画布高度覆盖最下方的槽卡与设备卡', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  const bottom = Math.max(
    ...t.cards.map(c => c.top + c.height),
    ...t.nodes.map(n => n.y + LAYOUT.devH / 2),
  )
  assert.equal(t.height, bottom + LAYOUT.padY)
})

test('选定边不产生「同 y 仍计交叉」的假阳性', () => {
  const edges = [
    { y1: 10, y2: 20 }, { y1: 20, y2: 20 }, { y1: 30, y2: 40 },
  ]
  assert.equal(countCrossings(edges), 0)
  assert.equal(countCrossings([{ y1: 10, y2: 40 }, { y1: 30, y2: 20 }]), 1)
})

/* ================= 链路摘要 ================= */
test('串口摘要：主行端口，次行波特率 + 帧格式', () => {
  const l = describeLink(BRIEFS['d-th'])
  assert.equal(l.l1, 'COM22')
  assert.equal(l.l2, '9600 8N1')
  assert.equal(l.unknown, false)
})

test('Modbus RTU 摘要带从站号', () => {
  const l = describeLink(BRIEFS['d-power'])
  assert.equal(l.l1, 'COM3')
  assert.match(l.l2, /9600 8N1/)
  assert.match(l.l2, /从站 6/)
})

test('Modbus TCP 摘要：IP:端口 + 从站号', () => {
  const l = describeLink(BRIEFS['d-ac'])
  assert.equal(l.l1, '192.168.1.60:502')
  assert.match(l.l2, /从站 1/)
})

test('host + port 且无从站号 → TCP 连接', () => {
  const l = describeLink(BRIEFS['d-cam'])
  assert.equal(l.l1, '192.168.1.70:8000')
  assert.equal(l.unknown, false)
})

test('无连接线索 → 未知连接（不误报）', () => {
  const l = describeLink(BRIEFS['d-gas'])
  assert.equal(l.l1, '—')
  assert.equal(l.l2, '未知连接')
  assert.equal(l.unknown, true)
})

test('briefs 为 null 时链路节点显骨架而不是「未知连接」', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: null })
  assert.ok(t.nodes.every(n => n.link === null))
})

/* ================= 图标映射 ================= */
test('22 类站房设备各自映射到专属图标（不落 generic）', () => {
  const categories = [
    'TH', 'CLEANLINESS', 'INDOOR_POLLUTANT',
    'POWER_METER', 'VOLTAGE_REGULATOR', 'UPS',
    'AIR_CONDITIONER_AC1', 'EXHAUST_FAN', 'LIGHTING',
    'SAMPLING_TUBE', 'ZERO_GAS_RELAY', 'STANDARD_GAS_SO2',
    'CALIBRATOR', 'FILTER_CHANGER_SO2', 'VALVE_GROUP_SO2', 'PM_ZERO_CHECK_PM10',
    'CUTTER_CHANGER_PM10', 'PAPER_TAPE_PM10',
    'SECURITY_ALARM', 'ELECTRONIC_FENCE', 'ACCESS_CONTROL', 'CAMERA_1',
  ]
  // 契约：glyphKindOf 收的是「类型槽键」（StationParamMeta.getType()，如 AIR_CONDITIONER.ac1），
  // 与 buildTopology 内部一致；枚举名先经 typeOf 转成槽键。
  const kinds = categories.map(slot => glyphKindOf({ slotType: typeOf(slot) }))
  assert.equal(kinds.length, 22)
  assert.ok(kinds.every(k => k && k !== 'generic'), '存在未登记图标的类型：' + kinds.join(','))
  assert.equal(new Set(kinds).size, 22, '存在两类设备共用同一图标：' + kinds.join(','))
  assert.ok(kinds.every(k => STATION_GLYPHS[k]))
})

test('槽类型键去掉实例后缀：AIR_CONDITIONER.ac1 → air_conditioner', () => {
  assert.equal(slotKindKey('AIR_CONDITIONER.ac1'), 'air_conditioner')
  assert.equal(slotKindKey('PM_ZERO_CHECK.pm10'), 'pm_zero_check')
  assert.equal(slotKindKey('TH'), 'th')
})

test('未知槽类型 / class 时回落 generic', () => {
  assert.equal(glyphKindOf({ slotType: 'UNKNOWN_SLOT' }), 'generic')
  assert.equal(glyphKindOf({ slotType: 'UNKNOWN_SLOT', className: 'air.conditioner' }), 'air_conditioner')
  assert.equal(glyphKindOf({}), 'generic')
})

test('质控仪（air.monitor.qc）用自己的图标，不随槽类型变化', () => {
  // 质控仪是「多槽复用的一体化仪表」：同一台设备绑温湿度/空调/照明都应画质控仪
  for (const slot of ['TH', 'AIR_CONDITIONER.ac1', 'LIGHTING', 'PM_ZERO_CHECK.pm10']) {
    assert.equal(glyphKindOf({ slotType: slot, className: 'air.monitor.qc', multiSlot: true }), 'qc',
      '质控仪在槽 ' + slot + ' 下画成了别的图标')
  }
  // 非质控仪设备仍按槽类型区分类别
  assert.equal(glyphKindOf({ slotType: 'TH', className: 'weather.sensor' }), 'th')
  assert.equal(glyphKindOf({ slotType: 'LIGHTING', className: 'default.sensor' }), 'lighting')
})

test('质控仪图标包含大屏 + 右下角开关 + USB 口三个识别要素', () => {
  const def = STATION_GLYPHS.qc
  assert.ok(def)
  const rects = def.items.filter(i => i.t === 'r')
  assert.ok(rects.length >= 2, '缺少机体/大屏矩形')
  // 大屏：宽度超过机体的 2/3，且在上半部
  assert.ok(rects[1].w >= 12 && rects[1].h >= 6, '大屏不够大')
  assert.ok(rects[1].y < rects[0].y + rects[0].h / 2, '大屏应在上半部')
  const circles = def.items.filter(i => i.t === 'c')
  assert.equal(circles.length, 1, '应有一个开关圆点')
  // 开关与 USB 都在右下半区（x > 12, y > 14）
  assert.ok(circles[0].cx > 12 && circles[0].cy > 14, '开关不在右下角')
  const usb = def.items.find(i => i.t === 'p' && /h2\.8v1\.5/.test(i.d))
  assert.ok(usb, '缺少 USB 梯形口')
})

test('图标定义完整：每项都有 label 与非空 items，且引用合法', () => {
  for (const kind of GLYPH_KINDS) {
    const def = STATION_GLYPHS[kind]
    assert.ok(def.label, kind + ' 缺 label')
    assert.ok(Array.isArray(def.items) && def.items.length > 0, kind + ' items 为空')
    for (const item of def.items) {
      assert.ok(['p', 'l', 'c', 'e', 'r', 'g'].includes(item.t), kind + ' 图元非法：' + item.t)
      if (item.t === 'g') assert.ok(Array.isArray(item.rotate) && item.items.length > 0)
    }
  }
})

test('拓扑节点带着图标 kind（按槽类型推断）', () => {
  const t = buildTopology({ bindings: BINDINGS, briefs: BRIEFS })
  assert.equal(t.nodes.find(n => n.deviceId === 'd-ac').kind, 'air_conditioner')
  assert.equal(t.nodes.find(n => n.deviceId === 'd-cam').kind, 'camera')
  assert.equal(t.nodes.find(n => n.deviceId === 'd-power').kind, 'power_meter')
  assert.equal(t.nodes.find(n => n.deviceId === 'd-th').kind, 'th')
})

/* ================= 列宽自适应 ================= */
test('窄容器（560）优先保设备卡与链路节点下限，不裁切', () => {
  const g = columnGeometry(560)
  assert.equal(g.devW >= LAYOUT.devMinW, true)
  assert.equal(g.linkW >= LAYOUT.linkMinW, true)
  assert.equal(g.linkLeft >= g.x1, true)
  assert.equal(g.devX + g.devW <= 560, true)
})

test('宽容器不超上限（设备卡 / 链路节点 / 槽卡都有 max）', () => {
  const g = columnGeometry(1600)
  assert.equal(g.devW, LAYOUT.devW)
  assert.equal(g.linkW, LAYOUT.linkW)
  assert.equal(g.pcolW, LAYOUT.pcolW)
})

/* ================= 路径 ================= */
test('同 y 时曲线退化为直线；不同 y 用三次贝塞尔', () => {
  assert.equal(curvePath(0, 10, 100, 10), 'M 0 10 L 100 10')
  assert.match(curvePath(0, 10, 100, 40), /^M 0 10 C /)
  assert.equal(hopPath(120, 160, 33), 'M 120 33 L 160 33')
})

console.log(`\n${passed} passed, ${failed} failed`)
if (failed) process.exit(1)
