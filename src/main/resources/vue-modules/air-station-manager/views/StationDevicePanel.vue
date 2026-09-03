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
    <header class="panel-header">
      <h1 class="panel-title">站房设备配置</h1>
      <p class="panel-sub">为 22 类站房设备（37 个类型槽）各配置物理设备并绑定到 logic station device。多实例类型（空调/标气/滤膜/摄像头等）逐槽独立配置。</p>
    </header>

    <p v-if="loadError" class="panel-err">加载失败:{{ loadError }}</p>

    <!-- 加载态挂 layout 容器（v-loading 指令）：booting/加载中显示 spinner，loaded 后渲染 sidebar+detail -->
    <div v-else class="layout" :class="{ 'layout-loading': !loaded }" v-loading="!loaded">
      <!-- 左 sidebar：37 类型槽分组全景 + 绑定徽标 -->
      <StationDeviceSidebar
        :groups="groups"
        :selectedParam="selected && selected.param"
        @select="selectParam"
      />

      <!-- 右 detail：绑定详情 + 操作区状态机 -->
      <StationDeviceDetail
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
import { reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useStationDeviceState } from '../composables/useStationDeviceState'
import { labelOf, typeOf } from '../stationParamMeta'
import { listCompatibleDevices, bindExisting, unbindType, waitAsmReady } from '../api/device'
import StationDeviceSidebar from '../components/StationDeviceSidebar.vue'
import StationDeviceDetail from '../components/StationDeviceDetail.vue'
import ReuseDeviceDialog from '../components/ReuseDeviceDialog.vue'
import ConfigFlowDialog from '../components/ConfigFlowDialog.vue'

const {
  loaded, loadError, selected, detailData,
  groups, referencedBy, referencingLabels,
  refresh, selectParam,
} = useStationDeviceState()

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
.station-device-panel { padding: 16px; min-width: 1200px; }
.asm-boot-banner { margin-bottom: 12px; }
.panel-header { margin-bottom: 16px; }
.panel-title { font-size: 20px; font-weight: 700; margin: 0 0 4px 0; }
.panel-sub { color: #6b7280; font-size: 13px; margin: 0; }
.panel-err { color: #dc2626; }

.layout { display: flex; gap: 16px; align-items: flex-start; }
/* 加载中给 v-loading 遮罩一个可视高度（sidebar/detail 未渲染时容器塌陷） */
.layout.layout-loading { min-height: 240px; }
</style>
