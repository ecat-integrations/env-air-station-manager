<template>
  <!-- 连接拓扑：把「站房设备」类型槽列表与「槽 → 链路 → 设备」的接线图合成一个面板。
       左列类型槽分组卡沿用侧栏的「六块独立白卡 + 已配/总数」形态；
       中列链路节点（串口 / IP）是真实中间节点，线在它左右沿各收口一次，摘要不会被线压住；
       右列设备卡展示设备名 / 型号 / 厂商 + 该类设备的传感器轮廓图标。
       纯展示组件：数据经 props，选中经 emit，自己只持有「宽度 / 悬停」两个纯视觉状态。
       版式与 ADM 参数设备页 ConnectionTopology 一致（同一套几何与配色）。 -->
  <section class="topo" :class="{ 'is-focus': focus }">
    <header class="topo-head">
      <span class="head-title">站房设备</span>
      <span class="head-count" :class="{ 'is-all': stats.total > 0 && stats.configured === stats.total }">
        {{ stats.configured }}/{{ stats.total }}
      </span>
      <AsmHintIcon :text="HINT_TEXT" label="站房设备说明" />
      <span class="head-sep" aria-hidden="true"></span>
      <span class="head-title">连接拓扑</span>
      <span class="head-count">{{ stats.devices }} 台设备 / {{ stats.configured }} 项</span>

      <div class="head-legend" aria-hidden="true">
        <span class="lg"><i class="lg-line"></i>连接</span>
        <span class="lg is-sib"><i class="lg-line"></i>同设备</span>
        <span class="lg is-on"><i class="lg-line"></i>当前</span>
      </div>
      <button type="button" class="head-btn" @click="$emit('toggle-focus')">
        {{ focus ? '收起' : '展开' }}
      </button>
    </header>

    <div ref="scrollEl" class="topo-scroll">
      <div class="topo-canvas" :style="{ height: layout.height + 'px' }">
        <!-- 连线：分两段（槽 → 链路节点 / 链路节点 → 设备），先铺白色描边衬底做「过桥」断口 -->
        <svg
          class="topo-svg"
          :width="width || 640"
          :height="layout.height"
          aria-hidden="true"
        >
          <path
            v-for="e in edges"
            :key="'halo-' + e.key"
            class="edge-halo"
            :class="{ 'is-on': e.state === 'hi' || e.state === 'sib' }"
            :d="e.d"
          />
          <path
            v-for="e in edges"
            :key="e.key"
            class="edge"
            :class="edgeClass(e.state)"
            :d="e.d"
          />
        </svg>

        <!-- ① 类型槽分组卡（白卡 + 卡头计数 + 行内 ✓ 与端点） -->
        <div
          v-for="c in layout.cards"
          :key="'card-' + c.name"
          class="pcard"
          :style="{ left: L.padX + 'px', top: c.top + 'px', width: cols.pcolW + 'px', height: c.height + 'px' }"
        >
          <div class="pcard-head">
            <span class="pcard-name">{{ c.name }}</span>
            <span class="pcard-count">{{ c.on }}/{{ c.total }}</span>
          </div>
          <button
            v-for="r in c.rows"
            :key="r.paramKey"
            type="button"
            class="prow"
            :class="{ 'is-selected': r.active, 'is-idle': !r.configured }"
            :style="{ top: (r.y - c.top - L.rowH / 2) + 'px' }"
            :aria-pressed="r.active ? 'true' : 'false'"
            :title="rowTitle(r)"
            @click="$emit('select', r.paramKey)"
          >
            <span class="prow-label">{{ r.label }}</span>
            <span class="prow-check">{{ r.configured ? '✓' : '' }}</span>
            <span class="prow-dot" aria-hidden="true"></span>
          </button>
        </div>

        <!-- ② 链路节点（端口 / IP + 帧格式） -->
        <div
          v-for="n in layout.nodes"
          :key="'link-' + n.deviceId"
          class="lnode"
          :class="[nodeClass(n.deviceId), { 'is-unknown': n.link && n.link.unknown }]"
          :style="{ left: cols.linkLeft + 'px', top: (n.y - L.linkH / 2) + 'px', width: cols.linkW + 'px' }"
          :title="linkTitle(n)"
        >
          <span class="lnode-ic">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 3v6" />
              <path d="M8.5 9h7a1.5 1.5 0 0 1 1.5 1.5v1A2.5 2.5 0 0 1 14.5 14h-5A2.5 2.5 0 0 1 7 11.5v-1A1.5 1.5 0 0 1 8.5 9z" />
              <path d="M12 14v3a4 4 0 0 0 4 4" />
            </svg>
          </span>
          <span class="lnode-txt">
            <span class="lnode-l1">{{ n.link ? n.link.l1 : '…' }}</span>
            <span class="lnode-l2">{{ n.link ? n.link.l2 : '加载中' }}</span>
          </span>
        </div>

        <!-- ③ 设备卡（设备名 / 型号 / 厂商 + 该类别轮廓图标 + 服务槽数） -->
        <button
          v-for="n in layout.nodes"
          :key="'dev-' + n.deviceId"
          type="button"
          class="dnode"
          :class="nodeClass(n.deviceId)"
          :style="{ left: cols.devX + 'px', top: (n.y - L.devH / 2) + 'px', width: cols.devW + 'px' }"
          :title="deviceTitle(n)"
          @mouseenter="hoverDevice = n.deviceId"
          @mouseleave="hoverDevice = null"
          @focus="hoverDevice = n.deviceId"
          @blur="hoverDevice = null"
          @click="onDeviceClick(n)"
        >
          <span class="dtile"><StationGlyph :kind="n.kind" /></span>
          <span class="dmeta">
            <span class="dname">{{ n.title || n.model || '未命名设备' }}</span>
            <span class="dmodel">{{ n.model || '—' }}</span>
            <span class="dvendor">{{ n.vendor || '—' }}</span>
          </span>
          <span class="dcnt">{{ n.paramKeys.length }} 项</span>
        </button>
      </div>
    </div>

    <p class="sr-only">{{ srText }}</p>
  </section>
</template>

<script setup>
defineOptions({ name: 'ConnectionTopology' })

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { buildTopology, columnGeometry, LAYOUT as L } from './buildTopology.js'
import { curvePath, hopPath } from './edgePath.js'
import AsmHintIcon from '../AsmHintIcon.vue'
import StationGlyph from './glyphs/StationGlyph.vue'

/** 页面级说明（原 StationDeviceSidebar 顶部 ? 气泡的文案，随「站房设备」列一起迁过来）。 */
const HINT_TEXT =
  '为 22 类站房设备（37 个传感器类型）各配置物理设备并绑定到逻辑站房设备。' +
  '多实例类型（空调/标气/滤膜/摄像头等）逐槽独立配置。' +
  '右侧连线表示该传感器经哪条链路（串口 / IP）连到哪台物理设备；' +
  '同一台设备被多个传感器共用时，多条线汇聚到同一个链路节点。'

const props = defineProps({
  /** ParamBinding[]（后端 /asm-monitor/device/params，顺序即侧栏展示顺序） */
  bindings: { type: Array, default: () => [] },
  /** { deviceId → {coordinate, className, commSettings} }；null = 摘要尚未加载（链路节点显骨架） */
  briefs: { type: Object, default: null },
  /** 选中槽键（StationParamMeta.getType()）；只影响高亮，不影响任何坐标或排序 */
  selectedParam: { type: String, default: null },
  /** 全屏画布模式（父级隐藏详情面板） */
  focus: { type: Boolean, default: false },
})
const emit = defineEmits(['select', 'toggle-focus'])

const scrollEl = ref(null)
const width = ref(0)
const hoverDevice = ref(null)

const layout = computed(() => buildTopology({
  bindings: props.bindings,
  briefs: props.briefs,
  selectedParam: props.selectedParam,
}))
const stats = computed(() => layout.value.stats)
const cols = computed(() => columnGeometry(width.value || 640))

/** 选中槽所属的设备 id（用设备 id 判定高亮，避免「同设备不同槽」误判） */
const selectedDeviceId = computed(() => {
  if (!props.selectedParam) return null
  const n = layout.value.nodes.find(x => x.paramKeys.includes(props.selectedParam))
  return n ? n.deviceId : null
})

/** 设备卡 / 链路节点三态：hi=当前设备，soft=悬停，dim=其余 */
function nodeState(deviceId) {
  if (!props.selectedParam) {
    if (!hoverDevice.value) return ''
    return hoverDevice.value === deviceId ? 'soft' : 'dim-soft'
  }
  const sel = selectedDeviceId.value
  if (!sel) return 'dim'
  return sel === deviceId ? 'hi' : 'dim'
}

/** 槽边四级：hi=当前那条，sib=同设备的其它槽，soft/dim-soft=悬停透视，dim=其余 */
function paramState(p) {
  if (!props.selectedParam) {
    if (!hoverDevice.value) return ''
    return hoverDevice.value === p.deviceId ? 'soft' : 'dim-soft'
  }
  const sel = selectedDeviceId.value
  if (!sel) return 'dim'
  if (p.paramKey === props.selectedParam) return 'hi'
  return sel === p.deviceId ? 'sib' : 'dim'
}

const edges = computed(() => {
  const g = cols.value
  if (!width.value || !layout.value.nodes.length) return []
  const byDev = new Map(layout.value.nodes.map(n => [n.deviceId, n]))
  const out = []
  for (const p of layout.value.params) {
    if (!p.deviceId) continue
    const n = byDev.get(p.deviceId)
    if (!n) continue
    out.push({
      key: 'p:' + p.paramKey,
      d: curvePath(g.x1, p.y, g.linkLeft, n.y),
      state: paramState(p),
    })
  }
  for (const n of layout.value.nodes) {
    out.push({
      key: 'd:' + n.deviceId,
      d: hopPath(g.linkRight, g.devX, n.y),
      state: nodeState(n.deviceId),
    })
  }
  return out
})

function edgeClass(state) {
  if (!state) return ''
  return 'is-' + state
}
function nodeClass(deviceId) {
  const st = nodeState(deviceId)
  if (!st) return ''
  return 'is-' + st
}

function linkTitle(n) {
  if (!n.link) return '连接摘要加载中'
  const parts = [n.link.full || n.link.title]
  if (n.crossGroup) parts.push('跨组设备')
  parts.push(`${n.paramKeys.length} 个传感器共用`)
  return parts.join('｜')
}
function deviceTitle(n) {
  const pieces = []
  if (n.title) pieces.push(n.title)
  if (n.model) pieces.push(n.model)
  if (n.vendor) pieces.push(`（${n.vendor}）`)
  const head = pieces.join(' · ')
  const link = n.link && n.link.full ? '｜' + n.link.full : ''
  return head + link
}

/** 槽行悬浮全文：名称 +（已配设备名 / 型号），未配时说明空态。 */
function rowTitle(r) {
  if (!r.configured) return r.label + '（未配置设备）'
  const bits = [r.label]
  if (r.model) bits.push(r.model)
  return bits.join(' · ')
}

const srText = computed(() => layout.value.nodes.map(n => {
  const labels = n.rows.map(r => r.label).join('、')
  const link = n.link && n.link.full ? n.link.full : '未知链路'
  return `${labels} 经 ${link} 连接至 ${n.title || n.model || '未命名设备'}`
}).join('；'))

function onDeviceClick(n) {
  if (!n.paramKeys.length) return
  if (n.paramKeys.includes(props.selectedParam)) return
  emit('select', n.paramKeys[0])
}

/* ---------- 宽度测量：只占一个 ResizeObserver，y 坐标与宽度无关 ---------- */
let ro = null
function measure() {
  const el = scrollEl.value
  if (!el) return
  const w = Math.round(el.clientWidth)
  if (w && w !== width.value) width.value = w
}

onMounted(() => {
  measure()
  ro = new ResizeObserver(measure)
  if (scrollEl.value) ro.observe(scrollEl.value)
})
onBeforeUnmount(() => {
  if (ro) ro.disconnect()
  ro = null
})

/** 选中项滚进可视区（只动本容器，不滚整页） */
function scrollSelectedIntoView() {
  const root = scrollEl.value
  if (!root) return
  const el = root.querySelector('.prow.is-selected') || root.querySelector('.dnode.is-hi')
  if (!el) return
  const r = root.getBoundingClientRect()
  const e = el.getBoundingClientRect()
  if (e.top >= r.top && e.bottom <= r.bottom) return
  root.scrollTop += e.top < r.top ? e.top - r.top : e.bottom - r.bottom
}
watch(() => props.selectedParam, () => nextTick(scrollSelectedIntoView))
// 首屏进入时槽列表是异步到的：等行渲染出来再把选中项滚进视野
watch(() => layout.value.params.length, n => { if (n) nextTick(scrollSelectedIntoView) })
</script>

<style scoped>
.topo {
  /* 宽度由父级 --topo-w 控制（默认 640，可拖 560–880）；空间不足时允许收窄到 560，
     宁可让详情变窄，也不把两侧一起挤到没法定点。 */
  flex: 0 1 auto;
  width: var(--topo-w, 640px);
  min-width: 560px;
  max-width: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 10px;
  overflow: hidden;
}
.topo.is-focus { flex: 1 1 auto; width: auto; min-width: 0; }

/* ---------- 头行：槽统计 + 拓扑统计 + 图例 + 展开 ---------- */
.topo-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 12px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  background: #fbfcfe;
  min-width: 0;
}
.head-title {
  font-size: 15px;
  font-weight: 650;
  color: #111827;
  letter-spacing: .02em;
  white-space: nowrap;
  flex: 0 0 auto;
}
.head-count {
  font-size: 11px;
  font-weight: 650;
  color: #4b5563;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 9px;
  padding: 0 7px;
  line-height: 17px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  flex: 0 0 auto;
}
.head-count.is-all { color: #059669; background: #ecfdf5; border-color: #a7f3d0; }
/* 分隔线两侧单独拉宽（20px），不影响整行其余 gap */
.head-sep { width: 1px; height: 14px; background: #e5e7eb; margin: 0 20px; flex: 0 0 auto; }

.head-legend {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 11px;
  font-size: 11px;
  color: #9ca3af;
  white-space: nowrap;
  /* 可压缩：面板收到 560px 时宁可图例被裁，也不能把「展开」按钮挤出面板 */
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
}
.lg { display: inline-flex; align-items: center; gap: 4px; }
.lg-line { width: 13px; height: 2px; border-radius: 1px; background: #cbd5e1; display: inline-block; }
.lg.is-sib .lg-line { background: #93c5fd; }
.lg.is-on .lg-line { background: #2563eb; height: 3px; }

.head-btn {
  flex: 0 0 auto;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  background: #fff;
  border-radius: 5px;
  padding: 2px 8px;
  font-size: 11px;
  color: #374151;
  cursor: pointer;
  white-space: nowrap;
}
.head-btn:hover { background: #f3f4f6; }

/* ---------- 画布：只竖向滚动 ---------- */
.topo-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  /* 常驻槽位：滚动条出现/消失不会让画布宽度跳变（宽度跳 → 重算 x → 抖动） */
  scrollbar-gutter: stable;
  background: #e9edf2;
  position: relative;
}
.topo-canvas { position: relative; }
.topo-svg { position: absolute; left: 0; top: 0; pointer-events: none; overflow: visible; }

/* ---------- 连线 ---------- */
.edge-halo {
  fill: none;
  stroke: #fff;
  stroke-width: 5;
  stroke-linecap: round;
}
.edge-halo.is-on { stroke: #eff6ff; }
.edge {
  fill: none;
  stroke: #cbd5e1;
  stroke-width: 1;
  stroke-linecap: round;
  transition: stroke .12s, stroke-width .12s, opacity .12s;
}
.edge.is-hi { stroke: #2563eb; stroke-width: 2; }
.edge.is-sib { stroke: #93c5fd; stroke-width: 1.5; }
.edge.is-dim { opacity: .2; }
.edge.is-soft { stroke: #94a3b8; stroke-width: 1.5; }
.edge.is-dim-soft { opacity: .45; }

/* ---------- 类型槽分组卡 ---------- */
/* 用 inset 阴影画 1px 描边而不是 border —— 绝对定位子元素以「内边距框」为基准，
   border 会让所有行整体下移 1px，而连线端点是按画布坐标算的，二者就对不齐了。 */
.pcard {
  position: absolute;
  box-sizing: border-box;
  background: #fff;
  box-shadow: inset 0 0 0 1px var(--el-border-color-lighter, #ebeef5),
              0 1px 2px rgba(15, 23, 42, .04);
  border-radius: 8px;
  overflow: hidden;
}
/* 组头：14px / 650 / letter-spacing .04em / 上 8 下 6。
   固定 height 是为了让 SVG 端点仍能纯数算（31 = 17 行盒 + 8 + 6），不改视觉。 */
.pcard-head {
  position: absolute;
  left: 0; right: 0; top: 0;
  box-sizing: border-box;
  height: 31px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 10px;
  font-size: 14px;
  font-weight: 650;
  color: #374151;
  letter-spacing: .04em;
  background: #f9fafb;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.pcard-count {
  font-size: 14px;
  font-weight: 500;
  color: #9ca3af;
  letter-spacing: 0;
  font-variant-numeric: tabular-nums;
}

/* 槽行：13px / 行高 22 / 上下 5px 内边距 = 32px 行高；左右各内缩 4px 让 hover 底色成圆角岛 */
.prow {
  position: absolute;
  left: 4px; right: 4px;
  box-sizing: border-box;
  height: 32px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  font-family: inherit;
  font-size: 13px;
  line-height: 22px;
  color: #1f2937;
  cursor: pointer;
  text-align: left;
  user-select: none;
  transition: background .12s, color .12s;
}
.prow:hover { background: #f3f4f6; }
.prow.is-idle .prow-label { color: #6b7280; }
.prow.is-selected { background: #eff6ff; color: #1d4ed8; }
.prow.is-selected .prow-label { font-weight: 650; }
.prow:focus-visible { outline: 2px solid #93c5fd; outline-offset: -2px; }

.prow-label {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.prow-check {
  flex: 0 0 16px;
  width: 16px;
  text-align: center;
  color: #10b981;
  font-weight: 700;
  font-size: 13px;
  line-height: 1;
}
.prow-dot {
  position: absolute;
  right: 0;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #94a3b8;
  box-shadow: 0 0 0 2px #fff;
}
.prow.is-idle .prow-dot {
  background: #fff;
  box-shadow: 0 0 0 1.5px #d1d5db, 0 0 0 3.5px #fff;
}
.prow.is-selected .prow-dot {
  background: #2563eb;
  box-shadow: 0 0 0 2px #fff, 0 0 0 4.5px #dbeafe;
}

/* ---------- 链路节点（线的两端各收口在它左右沿，摘要永不被线压住） ---------- */
.lnode {
  position: absolute;
  box-sizing: border-box;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 7px;
  border: 1px solid #dbeafe;
  background: #f5f9ff;
  border-radius: 7px;
  transition: border-color .12s, background .12s, box-shadow .12s, opacity .12s;
}
.lnode.is-hi {
  border-color: #bfdbfe;
  background: #eff6ff;
  box-shadow: 0 0 0 3px #eff6ff;
}
.lnode.is-dim { opacity: .42; }
.lnode.is-dim-soft { opacity: .55; }
.lnode.is-unknown {
  border: 1px dashed #d1d5db;
  background: #fafafa;
}
.lnode.is-unknown .lnode-ic { color: #9ca3af; }
.lnode.is-unknown .lnode-l1 { color: #6b7280; }

.lnode-ic { flex: 0 0 14px; display: flex; color: #3b82f6; }
.lnode-txt { min-width: 0; flex: 1 1 auto; display: flex; flex-direction: column; }
.lnode-l1 {
  font-size: 11px;
  font-weight: 700;
  line-height: 14px;
  color: #1e40af;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.lnode-l2 {
  font-size: 9.5px;
  font-weight: 600;
  line-height: 12px;
  color: #60a5fa;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- 设备卡 ---------- */
.dnode {
  position: absolute;
  box-sizing: border-box;
  height: 62px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 9px;
  border: 1px solid #e5e7eb;
  background: #fff;
  border-radius: 8px;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition: border-color .12s, background .12s, box-shadow .12s, opacity .12s;
}
.dnode:hover { border-color: #cbd5e1; box-shadow: 0 2px 7px rgba(15, 23, 42, .08); }
.dnode.is-hi {
  border-color: #bfdbfe;
  background: #f8fbff;
  box-shadow: 0 0 0 3px #eff6ff;
}
.dnode.is-soft { border-color: #cbd5e1; }
.dnode.is-dim { opacity: .55; }
.dnode.is-dim-soft { opacity: .7; }
.dnode:focus-visible { outline: 2px solid #93c5fd; outline-offset: 1px; }

.dtile {
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  border-radius: 7px;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  transition: background .12s, color .12s;
}
.dnode:hover .dtile { background: #e8edf4; }
.dnode.is-hi .dtile { background: #dbeafe; color: #2563eb; }

.dmeta { min-width: 0; flex: 1 1 auto; display: flex; flex-direction: column; }
.dname {
  font-size: 12.5px;
  font-weight: 650;
  line-height: 17px;
  color: #111827;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 型号：与右侧详情「型号」同款非等宽加粗（O/0 好区分） */
.dmodel {
  font-size: 10.5px;
  font-weight: 600;
  line-height: 14px;
  color: #374151;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dvendor {
  font-size: 10px;
  line-height: 13px;
  color: #9ca3af;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dcnt {
  position: absolute;
  top: 5px;
  right: 6px;
  font-size: 9.5px;
  font-weight: 650;
  line-height: 14px;
  color: #64748b;
  background: #f1f5f9;
  border-radius: 7px;
  padding: 0 5px;
}
.dnode.is-hi .dcnt { color: #1e40af; background: #dbeafe; }

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
