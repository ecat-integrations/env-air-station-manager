<template>
  <div class="asm-page station-device-panel">
    <!-- 启动装载门控：airstation logic 设备未就绪期间显骨架（勿把 registry 空渲染成"全部未配置"误导） -->
    <el-alert
      v-if="booting"
      class="asm-boot-banner"
      type="info"
      :closable="false"
      show-icon
      title="系统初始化中，设备配置装载中，请稍候…"
    />
    <p v-if="loadError" class="panel-err">加载失败:{{ loadError }}</p>

    <!-- 加载态挂 layout 容器（v-loading 指令）：booting/加载中显示 spinner，loaded 后渲染拓扑+详情 -->
    <div
      v-else
      class="layout"
      :class="{ 'layout-loading': !loaded }"
      :style="{ '--topo-w': topoWidth + 'px' }"
      v-loading="!loaded"
    >
      <!-- 左：站房设备（类型槽分组卡）+ 连接拓扑（链路节点 → 设备卡）合为一个面板，
           槽行右沿就是连线起点，两侧滚动同步、端点永不错位 -->
      <ConnectionTopology
        :bindings="paramBindings"
        :briefs="commBriefs"
        :selectedParam="selected && selected.param"
        :focus="topoFocus"
        @select="onSelectParam"
        @toggle-focus="topoFocus = !topoFocus"
      />

      <!-- 拓扑与详情之间的宽度手柄（双击复位） -->
      <div
        v-if="!topoFocus"
        class="split"
        :class="{ 'is-dragging': resizing }"
        title="拖动调整拓扑宽度（双击复位）"
        @mousedown="startResize"
        @dblclick="resetWidth"
      ></div>

      <!-- 右 detail：绑定详情 + 操作区状态机 -->
      <StationDeviceDetail
        v-if="!topoFocus"
        :paramBinding="selected"
        :detailData="detailData"
        :referencedBy="referencedBy"
        @add-device="onAddDevice"
        @replace="onReplace"
        @reconnect="onReconnect"
        @unbind="onUnbind"
      />
    </div>

    <!-- 复用弹窗（配置/更换共用，+配置新设备始终可见） -->
    <ReuseDeviceDialog
      :open="reuseDlg.open"
      :mode="reuseDlg.mode"
      :param="reuseDlg.param"
      :devices="reuseDlg.devices"
      :currentDeviceId="reuseDlg.currentDeviceId"
      :loading="reuseDlg.loading"
      :busy="reuseDlg.busy"
      @close="closeReuse"
      @bind="onReuseBind"
      @create-new="onReuseCreateNew"
    />

    <!-- 配置/更换/修改配置 flow 对话框（自管步进） -->
    <ConfigFlowDialog
      :open="cfgDlg.open"
      :mode="cfgDlg.mode"
      :param="cfgDlg.param"
      :oldDeviceId="cfgDlg.oldDeviceId"
      @close="closeCfg"
      @completed="onCfgCompleted"
    />
  </div>
</template>

<script setup>
// name 须取路由 name（integration-..._station_device）：宿主 AppMain <keep-alive :include=cachedViews>
// 按"组件 name"匹配，tagsView store 自动塞"路由 name"进 include；不等→不缓存→<transition mode=out-in>
// 离开 unmount→SPA 导航白屏（memory: vue-script-setup-keepalive-needs-defineoptions-name）。
defineOptions({ name: 'station_device' })
import { onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useStationDeviceState } from '../composables/useStationDeviceState'
import { labelOf, typeOf } from '../stationParamMeta'
import { listCompatibleDevices, bindExisting, unbindType, waitAsmReady } from '../api/device'
import ConnectionTopology from '../components/connection-topology/ConnectionTopology.vue'
import StationDeviceDetail from '../components/StationDeviceDetail.vue'
import ReuseDeviceDialog from '../components/ReuseDeviceDialog.vue'
import ConfigFlowDialog from '../components/ConfigFlowDialog.vue'

const {
  paramBindings, loaded, loadError, selected, detailData, commBriefs,
  referencedBy, referencingLabels,
  refresh, selectParam,
} = useStationDeviceState()

// 拓扑面板宽度（可拖 560–880，双击复位）；详情自动吃掉剩余宽度。
const TOPO_MIN = 560
const TOPO_MAX = 880
const TOPO_DEFAULT = 640
const TOPO_KEY = 'asm.topology.panelWidth'
const topoWidth = ref(TOPO_DEFAULT)
try {
  const saved = parseInt(localStorage.getItem(TOPO_KEY) || '', 10)
  if (saved >= TOPO_MIN && saved <= TOPO_MAX) topoWidth.value = saved
} catch (_) { /* 隐私模式下 localStorage 可能抛错：忽略，用默认宽度 */ }

// 全屏画布模式：临时隐藏详情，专心看全站接线
const topoFocus = ref(false)
const resizing = ref(false)
/** 拖拽中的清理函数（卸载时兜底回收，避免 window 监听泄漏） */
let stopResize = null

/** 点设备卡时选中它的首个槽；点槽行直接按槽选中（两条入口最终都落到 selectParam）。 */
function onSelectParam(param) {
  const binding = paramBindings.value.find(p => p.param === param)
  if (binding) selectParam(binding)
}

function startResize(e) {
  if (e.button !== 0) return
  e.preventDefault()
  resizing.value = true
  const startX = e.clientX
  const startW = topoWidth.value
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  const onMove = (ev) => {
    const w = startW + (ev.clientX - startX)
    topoWidth.value = Math.min(TOPO_MAX, Math.max(TOPO_MIN, Math.round(w)))
  }
  const onUp = () => {
    resizing.value = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    stopResize = null
    try { localStorage.setItem(TOPO_KEY, String(topoWidth.value)) } catch (_) { /* 忽略 */ }
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
  stopResize = onUp
}

function resetWidth() {
  topoWidth.value = TOPO_DEFAULT
  try { localStorage.setItem(TOPO_KEY, String(TOPO_DEFAULT)) } catch (_) { /* 忽略 */ }
}

onBeforeUnmount(() => {
  // 拖拽中卸载（切页）：回收挂在 window 上的监听，避免闭包一直持有已销毁组件的 ref
  if (stopResize) stopResize()
})

// ==================== 复用弹窗编排（配置/更换共用） ====================
const reuseDlg = reactive({
  open: false, mode: 'add', param: null,
  devices: [], currentDeviceId: '',
  loading: false, busy: false,
})

async function openReuse(mode) {
  const p = selected.value
  reuseDlg.mode = mode
  reuseDlg.param = p.param
  // 更换模式：当前已绑台标灰禁选；配置模式无当前台
  reuseDlg.currentDeviceId = mode === 'replace' ? (p.boundDeviceId || '') : ''
  reuseDlg.devices = []
  reuseDlg.loading = true
  reuseDlg.busy = false
  reuseDlg.open = true
  try {
    const res = await listCompatibleDevices(typeOf(p.param))
    // 引用标注注入（单一推导点 useStationDeviceState.referencingLabels 的消费方）：
    // 兼容端点只供设备事实（deviceId/coordinate/title/uniqueId），被哪些槽引用由 params
    // 数据推导（中文标签），不再消费后端 referencingParams（分叉已收口）。
    reuseDlg.devices = (res.data || []).map(d => ({ ...d, refs: referencingLabels(d.deviceId) }))
  } catch (e) {
    reuseDlg.loading = false
    // 兼容列表加载失败：留空列表 + 让用户走 +配置新设备（不阻断）
    console.error('[asm] 加载兼容设备失败', e)
    return
  }
  reuseDlg.loading = false
}

// 未配置主入口（Detail @add-device）→ 复用弹窗 mode=add
function onAddDevice() { openReuse('add') }
// 已配置"更换设备"（Detail @replace）→ 复用弹窗 mode=replace
function onReplace() { openReuse('replace') }

async function onReuseBind(chosenId) {
  reuseDlg.busy = true
  try {
    await bindExisting(typeOf(reuseDlg.param), {
      physicalDeviceId: chosenId,
      oldDeviceId: selected.value.boundDeviceId || null,
    })
    reuseDlg.busy = false
    reuseDlg.open = false
    await refresh()
  } catch (e) {
    reuseDlg.busy = false
    await ElMessageBox.alert('绑定失败:' + String(e.message || e), '提示', { type: 'error' })
  }
}

// "+ 配置新设备"（始终可见）：关复用弹窗 → 开 ConfigFlowDialog（mode 跟随，replace 带 oldDeviceId）
function onReuseCreateNew() {
  const mode = reuseDlg.mode
  const param = reuseDlg.param
  reuseDlg.open = false
  openCfg(mode, param)
}

function closeReuse() {
  if (reuseDlg.busy) return
  reuseDlg.open = false
}

// ==================== ConfigFlowDialog 编排 ====================
const cfgDlg = reactive({
  open: false, mode: 'add', param: null, oldDeviceId: null,
})

function openCfg(mode, param) {
  cfgDlg.mode = mode
  cfgDlg.param = param
  cfgDlg.oldDeviceId = mode === 'replace' ? (selected.value && selected.value.boundDeviceId) || null : null
  cfgDlg.open = true
}

// 已配置"修改配置"（Detail @reconnect）→ ConfigFlowDialog mode=reconnect（跳过 vendor）
function onReconnect() {
  openCfg('reconnect', selected.value.param)
}

function closeCfg() { cfgDlg.open = false }

async function onCfgCompleted() {
  cfgDlg.open = false
  await refresh()
}

// ==================== 移除 ====================
async function onUnbind() {
  const p = selected.value
  try {
    await ElMessageBox.confirm(`确认移除 ${labelOf(p.param)} 的设备绑定?`, '确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户点取消/ESC/关闭 → 原取消分支：不执行移除
    return
  }
  try {
    await unbindType(typeOf(p.param))
    await refresh()
  } catch (e) {
    await ElMessageBox.alert('移除失败:' + String(e.message || e), '提示', { type: 'error' })
  }
}

// ==================== 启动装载门控 ====================
// waitAsmReady 轮询 snapshot 非空（首台 logicdevice_station 设备就绪）后才 refresh 拉真实绑定态，
// 防止把起动窗口内的 registry 空渲染成"全部未配置"。
const booting = ref(false)
booting.value = true
waitAsmReady().then(() => { booting.value = false; refresh() })
</script>

<style scoped>
/* 高度吃满主区，左右两列齐高。min-width:0：窄窗时按钮区折行，不被右边框裁掉。 */
.station-device-panel {
  padding: 16px;
  min-width: 0;
  width: 100%;
  max-width: 100%;
  height: calc(100vh - 140px);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--el-bg-color-page, #f5f7fa);
}
.asm-boot-banner { margin-bottom: 12px; flex: 0 0 auto; }
.panel-err { color: #dc2626; flex: 0 0 auto; }

.layout {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  display: flex;
  align-items: stretch;
}
.layout.layout-loading { min-height: 240px; }

/* 拓扑与详情之间的宽度手柄（视觉上就是一条 6px 的拖拽带，hover/拖动时显灰） */
.split {
  flex: 0 0 6px;
  align-self: stretch;
  position: relative;
  z-index: 5;
  margin: 0 4px;
  cursor: col-resize;
}
.split::after {
  content: '';
  position: absolute;
  inset: 0 2px;
  border-radius: 2px;
  transition: background .12s;
}
.split:hover::after,
.split.is-dragging::after {
  background: #cbd5e1;
}
</style>
