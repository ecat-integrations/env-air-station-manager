<template>
  <!-- 复用弹窗（添加 / 更换 共用，镜像 ADM ReuseDeviceDialog）：
       - mode='add'    标题「配置设备 → X」，无当前台
       - mode='replace' 标题「更换设备 → X」，当前已绑台标灰禁选
       - 「+ 配置新设备」始终可见（footer 常驻）→ 关本弹窗跳 vendor→flow
       纯展示+选择：设备列表/当前台/加载态经 props；选中/绑定/新建经 emit，不调 API。 -->
  <el-dialog
    :model-value="open"
    :title="`${mode === 'replace' ? '更换设备' : '配置设备'} → ${paramLabel}`"
    width="600px"
    :close-on-click-modal="true"
    destroy-on-close
    @update:model-value="onVisible"
  >
    <p class="dlg-hint">系统已有同类型可复用的设备（型号匹配）:</p>
    <div v-if="loading" class="dlg-hint">加载中…</div>
    <ul v-else-if="devices.length" class="reuse-list">
      <li
        v-for="d in devices"
        :key="d.deviceId"
        class="reuse-item"
        :class="{
          chosen: chosenId === d.deviceId,
          current: d.deviceId === currentDeviceId,
          disabled: d.deviceId === currentDeviceId,
        }"
        @click="pick(d)"
      >
        <!-- 两级视觉层次（设计裁定 A：弱化系统标识，保留运维可读）：设备名为主信息，
             coordinate/uniqueId 是系统标识降为次行小字（mono 灰、超长省略、hover 提示完整值） -->
        <div class="reuse-row">
          <span class="reuse-title">{{ d.title }}</span>
        </div>
        <div class="reuse-sys" :title="`${d.coordinate} · ${d.uniqueId}`">{{ d.coordinate }} · {{ d.uniqueId }}</div>
        <div class="reuse-ref">
          <span v-if="d.deviceId === currentDeviceId" class="ref-tag current-tag">当前已绑</span>
          <span v-if="d.refs && d.refs.length">已被:[{{ d.refs.join('、') }}] 引用</span>
          <span v-else-if="d.deviceId !== currentDeviceId" class="free">未被引用</span>
        </div>
      </li>
    </ul>
    <div v-else class="reuse-empty">
      <span class="dlg-hint">无兼容设备。</span>
    </div>

    <div class="reuse-footer">
      <span class="dlg-hint">找不到合适的?</span>
      <el-button plain size="small" @click="$emit('create-new')">+ 配置新设备</el-button>
    </div>

    <template #footer>
      <el-button :disabled="busy" @click="$emit('close')">取消</el-button>
      <el-button type="primary" :disabled="!chosenId || busy" @click="$emit('bind', chosenId)">
        {{ busy ? '绑定中…' : '绑定选中设备' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
defineOptions({ name: 'ReuseDeviceDialog' })
import { ref, watch, computed } from 'vue'
import { labelOf } from '../stationParamMeta'

const props = defineProps({
  open: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },        // 'add' | 'replace'
  param: { type: String, default: null },        // 选中槽的枚举名
  devices: { type: Array, default: () => [] },
  currentDeviceId: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'bind', 'create-new'])

// el-dialog 关闭请求（X/遮罩点击）统一转 close 上抛，开关真相在父级 open prop；
// busy 期间不放行由父级 closeReuse 守卫（本组件不拦）。
function onVisible(v) {
  if (!v) emit('close')
}

// 选中态为组件内部选择（开关弹窗/换参数时重置）
const chosenId = ref('')
watch(() => [props.open, props.param], () => { chosenId.value = '' })

const paramLabel = computed(() => (props.param ? labelOf(props.param) : ''))

// 更换模式：当前已绑台不可选（disabled）；其余可选。
function pick(d) {
  if (d.deviceId === props.currentDeviceId) return
  chosenId.value = d.deviceId
}
</script>

<style scoped>
.dlg-hint { color: #6b7280; font-size: 13px; }

.reuse-list { list-style: none; margin: 12px 0; padding: 0; border: 1px solid #e5e7eb; border-radius: 6px; max-height: 300px; overflow: auto; }
.reuse-item { padding: 10px 12px; border-bottom: 1px solid #f3f4f6; cursor: pointer; }
.reuse-item:last-child { border-bottom: none; }
.reuse-item:hover { background: #f9fafb; }
.reuse-item.chosen { background: #eff6ff; }
.reuse-item.disabled { opacity: .55; cursor: not-allowed; }
.reuse-row { display: flex; gap: 10px; font-size: 13px; align-items: baseline; }
.reuse-title { color: #111827; font-weight: 500; }
.reuse-sys { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; color: #9ca3af; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.reuse-ref { font-size: 12px; color: #6b7280; margin-top: 4px; display: flex; gap: 8px; }
.ref-tag.current-tag { color: #d97706; }
.reuse-ref .free { color: #10b981; }
.reuse-empty { padding: 6px 0; }

.reuse-footer { display: flex; align-items: center; gap: 8px; padding: 10px 0 4px; }
</style>
