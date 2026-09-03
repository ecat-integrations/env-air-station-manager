<template>
  <!-- 右 detail：选中类型槽的绑定详情 + 操作区状态机（镜像 ADM ParamDetail）。
       纯展示组件：数据经 props，操作经 emit，不调 API。
       未配置 → 单一 🔵「配置设备」；已配置 → ⚪更换设备 + ⚪修改配置 + 🔴移除。 -->
  <section class="detail">
    <div v-if="!paramBinding" class="detail-empty">← 从左侧选择一个设备类型进行配置</div>

    <div v-else class="detail-body">
      <div class="detail-head">
        <h2 class="detail-title">{{ labelOf(paramBinding.param) }}</h2>
        <span class="detail-group">{{ groupOf(paramBinding.param) }}</span>
        <span class="detail-state" :class="stateClass">{{ stateText }}</span>
      </div>

      <!-- 已配置：设备识别 + 引用标注 + 原样 dump（零契约假设） -->
      <template v-if="paramBinding.configured">
        <div class="device-info">
          <!-- coordinate 是系统标识：mono 灰弱化（运维可读），与设备名等业务值区分 -->
          <div class="row"><span>厂商</span><span class="val sys-val" :title="paramBinding.coordinate">{{ paramBinding.coordinate || '—' }}</span></div>
          <div class="row"><span>设备名</span><span class="val">{{ paramBinding.title || '—' }}</span></div>
        </div>
        <div v-if="referencedBy.length" class="ref-note">
          该物理设备另被 [{{ referencedBy.join('、') }}] 引用
        </div>
        <div class="dump-label">设备配置内容(原样)</div>
        <pre v-if="detailData" class="dump">{{ detailData }}</pre>
        <p v-else class="detail-hint">加载中…</p>
      </template>

      <!-- 未配置：UNBOUND/NOT_CREATED 视觉合并为「未配置」，文字区分 -->
      <p v-else class="detail-hint">{{ paramBinding.state === 'UNBOUND' ? '曾配置,当前未绑定设备' : '暂未配置设备' }}</p>

      <!-- ===== 操作区(状态机) ===== -->
      <div v-if="!paramBinding.configured" class="actions actions-single">
        <el-button type="primary" @click="$emit('add-device')">配置设备</el-button>
      </div>
      <div v-else class="actions">
        <el-button @click="$emit('replace')">更换设备</el-button>
        <el-button @click="$emit('reconnect')">修改配置</el-button>
        <el-button type="danger" plain @click="$emit('unbind')">移除</el-button>
      </div>
    </div>
  </section>
</template>

<script setup>
defineOptions({ name: 'StationDeviceDetail' })
import { computed } from 'vue'
import { labelOf, groupOf } from '../stationParamMeta'

const props = defineProps({
  paramBinding: { type: Object, default: null },
  detailData: { type: String, default: '' },
  referencedBy: { type: Array, default: () => [] },
})
defineEmits(['add-device', 'replace', 'reconnect', 'unbind'])

const stateText = computed(() => (props.paramBinding?.configured ? '已配置' : '未配置'))
const stateClass = computed(() => (props.paramBinding?.configured ? 'configured' : 'unconfigured'))
</script>

<style scoped>
.detail {
  flex: 1; min-width: 0;
  background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 18px;
  min-height: 400px;
}
.detail-empty { color: #9ca3af; font-size: 14px; padding: 40px 0; text-align: center; }
.detail-head { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.detail-title { font-size: 18px; font-weight: 700; margin: 0; }
.detail-group { font-size: 12px; color: #6b7280; background: #f3f4f6; padding: 2px 8px; border-radius: 10px; }
.detail-state { font-size: 12px; padding: 2px 8px; border-radius: 10px; margin-left: auto; }
.detail-state.configured { color: #10b981; background: #ecfdf5; }
.detail-state.unconfigured { color: #9ca3af; background: #f3f4f6; }
.device-info { margin-bottom: 12px; }
.row { display: flex; font-size: 13px; margin: 3px 0; }
.row span:first-child { color: #6b7280; width: 64px; }
.row .val { color: #111827; }
.row .sys-val { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; color: #9ca3af; align-self: center; }
.ref-note {
  font-size: 12px; color: #d97706; background: #fffbeb; border: 1px solid #fde68a;
  border-radius: 4px; padding: 6px 10px; margin-bottom: 12px;
}
.dump-label { font-size: 12px; color: #6b7280; margin: 8px 0 4px; }
.dump {
  background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 12px;
  font-size: 12px; max-height: 320px; overflow: auto; white-space: pre-wrap; word-break: break-all;
  margin: 0 0 16px;
}
.detail-hint { color: #9ca3af; font-size: 13px; margin: 8px 0 16px; }

.actions { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.actions-single { justify-content: center; margin-top: 32px; }
</style>
