<template>
  <!-- 复用弹窗（添加 / 更换 共用，镜像 ADM ReuseDeviceDialog）：
       - mode='add'    标题「配置设备 → X」，无当前台
       - mode='replace' 标题「更换设备 → X」，当前已绑台标灰禁选
       - 「+ 配置新设备」始终可见（footer 常驻）→ 关本弹窗跳 vendor→flow
       纯展示+选择：设备列表/当前台/加载态经 props；选中/绑定/新建经 emit，不调 API。 -->
  <div v-if="open" class="dlg-overlay" @click.self="$emit('close')">
    <div class="dlg-box">
      <div class="dlg-head">
        <span class="dlg-title">{{ mode === 'replace' ? '更换设备' : '配置设备' }} → {{ paramLabel }}</span>
        <button class="dlg-x" @click="$emit('close')" :disabled="busy">×</button>
      </div>

      <div class="dlg-body">
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
            <div class="reuse-row">
              <span class="reuse-coord">{{ d.coordinate }}</span>
              <span class="reuse-title">{{ d.title }}</span>
              <span class="reuse-uid">{{ d.uniqueId }}</span>
            </div>
            <div class="reuse-ref">
              <span v-if="d.deviceId === currentDeviceId" class="ref-tag current-tag">当前已绑</span>
              <span v-if="d.referencingParams && d.referencingParams.length">已被:[{{ d.referencingParams.join('、') }}] 引用</span>
              <span v-else-if="d.deviceId !== currentDeviceId" class="free">未被引用</span>
            </div>
          </li>
        </ul>
        <div v-else class="reuse-empty">
          <span class="dlg-hint">无兼容设备。</span>
        </div>

        <div class="reuse-footer">
          <span class="dlg-hint">找不到合适的?</span>
          <button class="link-btn" type="button" @click="$emit('create-new')">+ 配置新设备</button>
        </div>

        <div class="dlg-actions">
          <button class="btn" @click="$emit('close')" :disabled="busy">取消</button>
          <button class="btn primary" :disabled="!chosenId || busy" @click="$emit('bind', chosenId)">
            {{ busy ? '绑定中…' : '绑定选中设备' }}
          </button>
        </div>
      </div>
    </div>
  </div>
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
defineEmits(['close', 'bind', 'create-new'])

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
.dlg-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: flex-start; justify-content: center; padding: 40px 0; z-index: 2000;
  overflow: auto;
}
.dlg-box { background: #fff; border-radius: 10px; width: 600px; max-width: 94vw; box-shadow: 0 8px 30px rgba(0,0,0,.2); }
.dlg-head { display: flex; justify-content: space-between; align-items: center; padding: 14px 18px; border-bottom: 1px solid #e5e7eb; }
.dlg-title { font-weight: 600; font-size: 16px; }
.dlg-x { border: none; background: none; font-size: 22px; cursor: pointer; color: #6b7280; line-height: 1; }
.dlg-body { padding: 18px; }
.dlg-hint { color: #6b7280; font-size: 13px; }

.reuse-list { list-style: none; margin: 12px 0; padding: 0; border: 1px solid #e5e7eb; border-radius: 6px; max-height: 300px; overflow: auto; }
.reuse-item { padding: 10px 12px; border-bottom: 1px solid #f3f4f6; cursor: pointer; }
.reuse-item:last-child { border-bottom: none; }
.reuse-item:hover { background: #f9fafb; }
.reuse-item.chosen { background: #eff6ff; }
.reuse-item.disabled { opacity: .55; cursor: not-allowed; }
.reuse-row { display: flex; gap: 10px; font-size: 13px; align-items: baseline; }
.reuse-coord { color: #6b7280; min-width: 120px; }
.reuse-title { color: #111827; font-weight: 500; }
.reuse-uid { color: #9ca3af; font-size: 12px; margin-left: auto; }
.reuse-ref { font-size: 12px; color: #6b7280; margin-top: 4px; display: flex; gap: 8px; }
.ref-tag.current-tag { color: #d97706; }
.reuse-ref .free { color: #10b981; }
.reuse-empty { padding: 6px 0; }

.reuse-footer { display: flex; align-items: center; gap: 8px; padding: 10px 0 4px; }
.link-btn { background: none; border: none; color: #2563eb; cursor: pointer; font-size: 13px; padding: 0; text-decoration: none; }
.link-btn:hover { text-decoration: underline; }

.dlg-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.btn { font-size: 13px; padding: 6px 14px; border: 1px solid #d1d5db; border-radius: 4px; background: #fff; cursor: pointer; }
.btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
.btn:hover { opacity: .85; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
</style>
