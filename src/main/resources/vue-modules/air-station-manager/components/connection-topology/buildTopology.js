// 连接拓扑数据构建（纯函数，无 Vue / 无 DOM，可单测）。
//
// 形态与 ADM 参数设备页一致：三列二分图 —— 左「类型槽分组卡」 → 中「链路节点」 → 右「设备卡」。
//   · 槽节点 y 由行序推出（rowH 固定），链路/设备 y 由所连槽 y 的中位数推出，
//     故整套坐标与 DOM 测量无关，一次计算即可（宽度只影响 x，见 columnGeometry）。
//   · 每台设备只出现一次：同一物理设备被 N 个槽绑定时，N 条线汇聚到同一个链路节点，
//     再单线连到设备卡。这样「多槽对一」在视觉上就是总线汇聚，不会重复画设备。
//
// 交叉处理（槽序号区间交错时必然出现，如一台电源同时服务「供配电」与「站房动环」）：
//   ① 设备按所连槽序号中位数排序（并列按最小序号，保证选中变化不重排）；
//   ② 纵向避让：相邻设备间距不足 minGap 时下推，最多 2 轮；
//   ③ 渲染侧用过桥效果（白描边衬底）让交叉可读，见 ConnectionTopology.vue。
import { GROUP_ORDER, groupOf, labelOf, typeOf } from '../../stationParamMeta.js'
import { resolveConnectionType } from '../comm/resolveConnectionType.js'
import { connectionSummary } from '../comm/formatters.js'
import { glyphKindOf } from './glyphs/glyphKind.js'

/**
 * 版式常量：与 CSS 一一对应，改这里要同步改 ConnectionTopology.vue 的 style。
 */
export const LAYOUT = {
  padX: 18,          // 画布左右留白（槽卡与设备卡都不贴边）
  padY: 18,          // 画布上下留白
  rowH: 32,          // 槽行高（= 13px 文字 22px 行高 + 上下各 5px 内边距）
  grpH: 31,          // 分组卡头行高（= 14px 文字 + 上 8 / 下 6 内边距）
  cardGapY: 8,       // 分组卡之间的灰轨间隙
  cardPadTop: 4,     // 分组卡顶部内边距
  cardPadY: 4,       // 分组卡底部内边距
  pcolW: 168,        // 类型槽卡宽（上限）
  pcolMinW: 140,
  // 链路节点：ASM 站房设备常走串口，主行形如「COM22」，次行「9600 8N1」，
  // 132 足够容纳「192.168.1.60:502」这类最长的 IP:端口（与 ADM 同值）。
  linkW: 132,
  linkMinW: 86,
  linkH: 38,
  linkGapX: 30,      // 链路节点右沿 → 设备卡左沿
  devW: 190,         // 设备卡宽（上限）
  devMinW: 158,
  devH: 62,
  devGapY: 74,       // 相邻设备卡中心距下限（devH 62 + 12 视觉间隙）
  minCurveX: 56,     // 曲线空间下限（再小就成直线了，但仍可读）
  hardMinCurveX: 40, // 曲线空间硬下限：宁可线更直，也要保住链路节点的端口/IP 全文
}

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v))

/** 中位数（偶数取两者均值） */
export function median(arr) {
  if (!arr || !arr.length) return 0
  const a = [...arr].sort((x, y) => x - y)
  return a.length % 2 ? a[(a.length - 1) / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2
}

/**
 * 三列的 x 坐标分配（随容器宽度自适应）。
 * 空间不足时先压设备卡、再压链路节点，最后才挤曲线空间——保证「设备卡不被裁切」优先。
 *
 * @param {number} width 画布可用宽度（滚动容器 clientWidth）
 */
export function columnGeometry(width) {
  const L = LAYOUT
  // 槽卡先按上限宽度试算；空间紧到保不住链路节点全文时，槽卡可退到 pcolMinW 让位。
  let pcolW = L.pcolW
  let x1 = L.padX + pcolW
  let avail = Math.max(0, width - x1 - L.padX)

  let devW = L.devW
  let linkW = L.linkW
  const ideal = L.linkW + L.linkGapX + L.devW + L.minCurveX
  if (avail < ideal) {
    // 压缩顺序（重要）：曲线空间先让位 → 设备卡压到下限 → 槽卡让位 → 最后才压链路节点。
    // 链路节点里是「COM21 / 192.168.1.60:502」这类唯一标识，丢一个字就没法认；
    // 设备卡挤窄只会让型号/厂商省略（详情卡里仍完整）；曲线挤窄只是线更直；
    // 槽卡挤窄只是名称变短（名称/✓/端点都按 flex 自适应，不会丢内容）。
    const needFullLink = L.linkW + L.linkGapX + L.devMinW + L.hardMinCurveX
    if (avail < needFullLink) {
      // 用槽卡的下限宽度换空间，最多让出 (pcolW - pcolMinW)，刚好够补缺口就停
      const shrink = Math.min(L.pcolW - L.pcolMinW, needFullLink - avail)
      pcolW = L.pcolW - shrink
      x1 = L.padX + pcolW
      avail = Math.max(0, width - x1 - L.padX)
    }
    if (avail >= needFullLink) {
      linkW = L.linkW
      devW = clamp(avail - linkW - L.linkGapX - L.minCurveX, L.devMinW, L.devW)
    } else {
      devW = L.devMinW
      linkW = clamp(avail - devW - L.linkGapX - L.hardMinCurveX, L.linkMinW, L.linkW)
    }
    // 两者都触底仍放不下时，继续让曲线空间让位（宁可直线，也不裁卡片）
    if (linkW + L.linkGapX + devW > avail) {
      devW = Math.max(L.devMinW, avail - linkW - L.linkGapX)
    }
  }

  const devX = x1 + Math.max(0, avail - devW)
  const linkRight = devX - L.linkGapX
  const linkLeft = linkRight - linkW

  return {
    x1, linkLeft, linkRight, devX, pcolW, linkW, devW,
    curveX: Math.max(0, linkLeft - x1),
  }
}

/** 链路摘要拆成两行：主行认端口/地址，次行放帧格式/协议（超长由 CSS 省略 + title 兜底）。 */
export function describeLink(brief) {
  const resolved = resolveConnectionType(
    { comm_settings: brief && brief.commSettings ? brief.commSettings : null,
      class: brief && brief.className ? brief.className : null },
    { coordinate: (brief && brief.coordinate) || '' },
  )
  const full = connectionSummary(resolved)
  if (!full) {
    return { type: resolved.type, title: resolved.title, full: '', l1: '—', l2: '未知连接', unknown: true }
  }
  const parts = full.split(' · ')
  const l1 = parts[0]
  const l2 = parts.length > 1 ? parts.slice(1).join(' · ') : resolved.title
  return { type: resolved.type, title: resolved.title, full, l1, l2, unknown: resolved.type === 'unknown' }
}

/**
 * 主入口：绑定列表 + 连接摘要 → 可直接渲染的几何数据。
 *
 * @param {object} p
 * @param {Array}  p.bindings       ParamBinding[]（顺序 = StationParamMeta 声明顺序，即侧栏展示顺序）
 * @param {object} p.briefs         { deviceId → CommBrief }；null = 尚未加载（链路节点渲染骨架）
 * @param {string} p.selectedParam  当前选中的槽键字面量（仅打 active 标记，不影响任何坐标/顺序）
 * @returns {{ cards, params, nodes, height, stats }}
 */
export function buildTopology({ bindings = [], briefs = null, selectedParam = null } = {}) {
  const L = LAYOUT

  /* ---------- ① 类型槽分组卡（保留侧栏「六块独立白卡 + 计数」的形态） ---------- */
  const cards = []
  const params = []
  let y = L.padY
  let seq = 0

  for (const group of GROUP_ORDER) {
    const items = bindings.filter(b => groupOf(b.param) === group)
    if (!items.length) continue

    const top = y
    const rows = items.map((b, i) => {
      const row = {
        paramKey: b.param,
        label: labelOf(b.param),
        group,
        index: seq++,
        y: top + L.grpH + L.cardPadTop + i * L.rowH + L.rowH / 2,
        deviceId: b.configured ? (b.boundDeviceId || null) : null,
        configured: !!b.configured,
        active: b.param === selectedParam,
        crossGroup: false,
        slotType: typeOf(b.param),
        model: b.deviceModel || '',
      }
      params.push(row)
      return row
    })

    const height = L.grpH + L.cardPadTop + items.length * L.rowH + L.cardPadY
    cards.push({
      name: group,
      top,
      height,
      on: rows.filter(r => r.configured).length,
      total: rows.length,
      rows,
    })
    y = top + height + L.cardGapY
  }
  const cardBottom = cards.length ? cards[cards.length - 1].top + cards[cards.length - 1].height : L.padY

  /* ---------- ② 设备节点（按 deviceId 归并，一台设备只出现一次） ---------- */
  const byId = new Map()
  for (const row of params) {
    if (!row.deviceId) continue
    let d = byId.get(row.deviceId)
    if (!d) {
      const b = bindings.find(x => x.param === row.paramKey) || {}
      d = {
        deviceId: row.deviceId,
        title: b.title || '',
        model: b.deviceModel || '',
        vendor: b.deviceVendor || '',
        coordinate: b.coordinate || '',
        deviceClass: b.deviceClass || '',
        brief: briefs ? briefs[row.deviceId] || null : null,
        rows: [],
        indices: [],
        groups: new Set(),
        y: 0,
      }
      byId.set(row.deviceId, d)
    }
    d.rows.push(row)
    d.indices.push(row.index)
    d.groups.add(row.group)
  }

  const nodes = [...byId.values()].map(d => {
    const ys = d.rows.map(r => r.y)
    return {
      ...d,
      y: median(ys),
      medianIndex: median(d.indices),
      minIndex: Math.min(...d.indices),
      crossGroup: d.groups.size > 1,
      groupList: [...d.groups],
      paramKeys: d.rows.map(r => r.paramKey),
      active: d.rows.some(r => r.active),
      link: briefs === null ? null : describeLink(d.brief),
      // 图标：设备自身声明的类别优先（同一台设备可能被多个槽共用），槽类型兜底 —— 见 glyphs/glyphKind.js
      kind: glyphKindOf({
        slotType: typeOf(d.rows[0].paramKey),
        className: (d.brief && d.brief.className) || d.deviceClass,
        multiSlot: d.rows.length > 1,
      }),
    }
  })

  // 跨组设备：给它服务的槽行打标（详情侧可据 groupList 提示「横跨两组」）
  for (const n of nodes) {
    if (n.crossGroup) n.rows.forEach(r => { r.crossGroup = true })
  }

  /* ---------- ③ 排序 + 纵向避让（顺序与选中态无关，选中只改 active） ---------- */
  nodes.sort((a, b) => a.medianIndex - b.medianIndex || a.minIndex - b.minIndex)

  if (nodes.length) {
    nodes[0].y = Math.max(nodes[0].y, L.padY + L.devH / 2)
    for (let pass = 0; pass < 2; pass++) {
      for (let i = 1; i < nodes.length; i++) {
        const gap = nodes[i].y - nodes[i - 1].y
        if (gap < L.devGapY) nodes[i].y = nodes[i - 1].y + L.devGapY
      }
    }
  }

  const devBottom = nodes.length ? nodes[nodes.length - 1].y + L.devH / 2 : 0
  const height = Math.max(cardBottom, devBottom) + L.padY

  return {
    cards,
    params,
    nodes,
    height,
    stats: {
      total: bindings.length,
      configured: params.filter(p => p.configured).length,
      devices: nodes.length,
      categories: new Set(nodes.map(n => n.kind)).size,
    },
  }
}

/**
 * 统计交叉数（仅测试/诊断用）：两条边端点相对次序相反即相交。
 * @param {Array<{y1:number,y2:number}>} edges
 */
export function countCrossings(edges) {
  let n = 0
  for (let i = 0; i < edges.length; i++) {
    for (let j = i + 1; j < edges.length; j++) {
      const a = edges[i]
      const b = edges[j]
      if (a.y1 === b.y1 || a.y2 === b.y2) continue
      if ((a.y1 - b.y1) * (a.y2 - b.y2) < 0) n++
    }
  }
  return n
}
