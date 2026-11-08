<template>
  <!-- 右 detail：选中类型槽的绑定详情 + 操作区（镜像 ADM ParamDetail）。
       纯展示组件：数据经 props，操作经 emit，不调 API。
       未配置 → 单一「配置设备」；已配置 → 更换设备 + 修改配置 + 移除，右对齐钉在卡底。 -->
  <section class="detail">
    <div v-if="!paramBinding" class="detail-empty">
      <span class="empty-arrow">←</span>
      <span>从左侧选择一个设备类型进行配置</span>
    </div>

    <template v-else>
      <div class="detail-body">
        <div class="detail-head">
          <h2 class="detail-title">{{ labelOf(paramBinding.param) }}</h2>
          <span class="detail-group">{{ groupOf(paramBinding.param) }}</span>
          <span class="detail-state" :class="stateClass">{{ stateText }}</span>
        </div>

        <template v-if="paramBinding.configured">
          <div class="device-info">
            <div class="info-row">
              <span class="info-k">设备名</span>
              <span class="info-v">{{ paramBinding.title || '—' }}</span>
            </div>
            <div class="info-row">
              <span class="info-k">型号</span>
              <span class="info-v is-strong">{{ paramBinding.deviceModel || '—' }}</span>
            </div>
            <div class="info-row">
              <span class="info-k">厂商</span>
              <span class="info-v">{{ paramBinding.deviceVendor || paramBinding.coordinate || '—' }}</span>
            </div>
            <div v-if="paramBinding.coordinate" class="info-row">
              <span class="info-k">集成坐标</span>
              <span class="info-v is-mono">{{ paramBinding.coordinate }}</span>
            </div>
          </div>
          <div v-if="referencedBy.length" class="ref-note">
            该物理设备另被 [{{ referencedBy.join('、') }}] 引用
          </div>
          <div class="dump-block">
            <div class="dump-label">设备配置内容(原样)</div>
            <pre v-if="detailData" class="dump">{{ detailData }}</pre>
            <p v-else class="dump is-loading">加载中…</p>
          </div>
        </template>

        <p v-else class="detail-hint is-empty">
          {{ paramBinding.state === 'UNBOUND' ? '曾配置,当前未绑定设备' : '暂未配置设备' }}
        </p>
      </div>

      <!-- 操作区钉在卡底：三按钮右对齐，不随 JSON 长短上下跳。 -->
      <div v-if="!paramBinding.configured" class="actions actions-single">
        <button class="btn primary" type="button" @click="$emit('add-device')">配置设备</button>
      </div>
      <div v-else class="actions">
        <button class="btn" type="button" @click="$emit('replace')">更换设备</button>
        <button class="btn" type="button" @click="$emit('reconnect')">修改配置</button>
        <button class="btn danger" type="button" @click="$emit('unbind')">移除</button>
      </div>
    </template>
  </section>
</template>

<script setup>
defineOptions({ name: 'StationDeviceDetail' })
import { computed } from 'vue'
import { groupOf, labelOf } from '../stationParamMeta'

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
  flex: 1 1 0; min-width: 0; min-height: 0;
  display: flex; flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 10px;
  padding: 18px 20px 16px;
}

.detail-empty {
  flex: 1;
  color: #9ca3af; font-size: 14px;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
}
.empty-arrow { font-size: 22px; color: #d1d5db; }

.detail-body {
  flex: 1 1 auto; min-height: 0;
  display: flex; flex-direction: column;
}

.detail-head {
  flex: 0 0 auto;
  display: flex; align-items: center; gap: 10px;
  padding-bottom: 12px; margin-bottom: 14px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.detail-title { font-size: 18px; font-weight: 700; margin: 0; color: #111827; }
.detail-group { font-size: 12px; color: #6b7280; background: #f3f4f6; padding: 2px 8px; border-radius: 10px; }
.detail-state { font-size: 12px; padding: 2px 8px; border-radius: 10px; margin-left: auto; font-weight: 500; }
.detail-state.configured { color: #10b981; background: #ecfdf5; }
.detail-state.unconfigured { color: #9ca3af; background: #f3f4f6; }

.device-info {
  flex: 0 0 auto;
  background: #f9fafb; border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px; padding: 10px 12px; margin-bottom: 12px;
}
.info-row { display: flex; font-size: 13px; line-height: 22px; }
.info-row + .info-row { margin-top: 2px; }
.info-k { color: #6b7280; width: 64px; flex-shrink: 0; }
.info-v { color: #111827; min-width: 0; word-break: break-all; }
.info-v.is-strong { font-weight: 600; }
.info-v.is-mono { font-size: 12px; color: #9ca3af; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

.ref-note {
  flex: 0 0 auto;
  font-size: 12px; color: #d97706; background: #fffbeb; border: 1px solid #fde68a;
  border-radius: 4px; padding: 6px 10px; margin-bottom: 12px;
}

.dump-block {
  flex: 1 1 auto; min-height: 0;
  display: flex; flex-direction: column;
}
.dump-label { flex: 0 0 auto; font-size: 12px; color: #6b7280; margin: 0 0 4px; }
.dump {
  flex: 1 1 auto; min-height: 0;
  background: #f9fafb; border: 1px solid var(--el-border-color-lighter, #ebeef5); border-radius: 6px;
  padding: 12px;
  font-size: 12px; overflow: auto; white-space: pre-wrap; word-break: break-all;
  margin: 0;
}
.dump.is-loading { color: #9ca3af; font-family: inherit; }

.detail-hint { color: #9ca3af; font-size: 13px; margin: 8px 0 16px; }
.detail-hint.is-empty {
  flex: 1 1 auto; min-height: 0; margin: 0;
  display: flex; align-items: center; justify-content: center;
  padding: 28px 16px; text-align: center;
  border: 1px dashed #e5e7eb; border-radius: 6px;
}

.actions {
  flex: 0 0 auto;
  display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end;
  margin-top: 12px; padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  min-width: 0;
}
.actions-single { justify-content: center; }

.btn {
  flex: 0 1 auto;
  font-size: 13px; padding: 6px 14px; border: 1px solid #d1d5db; border-radius: 4px;
  background: #fff; color: #374151; cursor: pointer;
  transition: background .12s, border-color .12s, color .12s;
}
.btn:hover {
  background: #f3f4f6;
  border-color: #9ca3af;
  color: #111827;
}
.btn:active { background: #e5e7eb; }
.btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
.btn.primary:hover { background: #1d4ed8; border-color: #1d4ed8; color: #fff; }
.btn.primary:active { background: #1e40af; }
.btn.danger { color: #dc2626; border-color: #fecaca; background: #fff; }
.btn.danger:hover { background: #fef2f2; border-color: #dc2626; color: #b91c1c; }
.btn.danger:active { background: #fee2e2; }
</style>
