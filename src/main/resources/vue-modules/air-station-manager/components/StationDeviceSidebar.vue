<template>
  <!-- 左 sidebar：37 类型槽分组全景（镜像 ADM ParamSidebar，无 NOx 归并——ASM 槽各自独立）。
       纯展示组件：数据经 props 传入，选中经 emit 上报，不持业务状态、不调 API。 -->
  <aside class="sidebar">
    <div class="sidebar-head">
      <span class="sidebar-head-title">站房设备</span>
      <span
        class="sidebar-head-count"
        :class="{ 'is-all': configuredTotal > 0 && configuredTotal === total }"
        :title="'已配置 ' + configuredTotal + ' / ' + total"
      >{{ configuredTotal }}/{{ total }}</span>
      <AsmHintIcon :text="HINT_TEXT" label="站房设备说明" />
    </div>

    <div class="sidebar-scroll">
      <div v-for="g in groups" :key="g.name" class="param-group">
        <div class="group-title">
          <span class="group-name">{{ g.name }}</span>
          <span class="group-count">{{ configuredOf(g) }}/{{ g.items.length }}</span>
        </div>
        <ul class="param-list">
          <li
            v-for="p in g.items"
            :key="p.param"
            class="param-item"
            :class="{ selected: selectedParam === p.param, 'is-configured': p.configured }"
            @click="$emit('select', p)"
          >
            <span class="param-label" :title="labelOf(p.param)">{{ labelOf(p.param) }}</span>
            <!-- 绑定设备型号：仅已配且有型号时渲染。未配置不留空占位。 -->
            <span
              v-if="p.deviceModel"
              class="param-model"
              :title="modelTitleOf(p)"
            >{{ p.deviceModel }}</span>
            <span v-if="p.configured" class="param-check" title="已配置">✓</span>
            <span v-else class="param-check is-empty" title="未配置"></span>
          </li>
        </ul>
      </div>
    </div>
  </aside>
</template>

<script setup>
defineOptions({ name: 'StationDeviceSidebar' })
import { computed } from 'vue'
import { labelOf } from '../stationParamMeta'
import AsmHintIcon from './AsmHintIcon.vue'

const HINT_TEXT =
  '为 22 类站房设备（37 个类型槽）各配置物理设备并绑定到逻辑站房设备。' +
  '多实例类型（空调/标气/滤膜/摄像头等）逐槽独立配置绑定设备的型号。' 
const props = defineProps({
  groups: { type: Array, required: true },
  selectedParam: { type: String, default: null },
})
defineEmits(['select'])

function configuredOf(g) {
  return (g.items || []).reduce((n, p) => n + (p.configured ? 1 : 0), 0)
}

const total = computed(() => props.groups.reduce((n, g) => n + ((g.items && g.items.length) || 0), 0))
const configuredTotal = computed(() => props.groups.reduce((n, g) => n + configuredOf(g), 0))

/** 型号 chip 的 hover 全文：「设备名 · 型号（厂商）」，缺项各自省略。 */
function modelTitleOf(p) {
  const name = p.title ? p.title + ' · ' : ''
  const vendor = p.deviceVendor ? `（${p.deviceVendor}）` : ''
  return name + p.deviceModel + vendor
}
</script>

<style scoped>
/* 灰轨 + 分组白卡，与 ADM 参数设备侧栏同款。 */
.sidebar {
  flex: 0 1 22%; min-width: 280px; max-width: 340px;
  min-height: 0;
  margin-bottom: 0;
  display: flex; flex-direction: column;
  background: #e5e7eb;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 10px;
  overflow: hidden;
  padding: 8px 16px;
}

.sidebar-head {
  flex: 0 0 auto; display: flex; align-items: center; gap: 6px;
  padding: 11px 12px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.sidebar-head-title { font-size: 15px; font-weight: 650; color: #111827; letter-spacing: .02em; }
.sidebar-head-count {
  font-size: 11px; font-weight: 650; color: #4b5563; line-height: 17px;
  background: #fff; border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 9px; padding: 0 7px;
}
.sidebar-head-count.is-all {
  color: #059669; background: #ecfdf5; border-color: #a7f3d0;
}

.sidebar-scroll {
  flex: 1 1 auto; min-height: 0;
  overflow-y: auto; overflow-x: hidden;
  padding: 8px 8px 28px;
}

.param-group {
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  margin-bottom: 0;
  overflow: hidden;
}
.param-group + .param-group { margin-top: 8px; }

.group-title {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  font-size: 14px; font-weight: 650; color: #374151;
  letter-spacing: .04em;
  padding: 8px 10px 6px;
  background: #f9fafb;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  position: sticky; top: 0; z-index: 1;
}
.group-name { min-width: 0; }
.group-count { color: #9ca3af; font-weight: 500; font-variant-numeric: tabular-nums; }

.param-list { list-style: none; margin: 0; padding: 4px 0; }
.param-item {
  display: flex; align-items: center; gap: 8px;
  margin: 0 4px; padding: 5px 8px;
  cursor: pointer; user-select: none;
  border-radius: 6px;
  font-size: 13px; line-height: 22px;
  color: #1f2937;
  transition: background .12s, color .12s;
}
.param-item:hover { background: #f3f4f6; }
.param-item.selected {
  background: #eff6ff;
  color: #1d4ed8;
}
.param-item.selected .param-label { font-weight: 650; }
.param-item:not(.is-configured) .param-label { color: #6b7280; }
.param-item.selected:not(.is-configured) .param-label { color: #1d4ed8; }

.param-label {
  flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.param-check {
  flex: 0 0 16px; width: 16px;
  color: #10b981; font-weight: 700;
  text-align: center;
}
.param-check.is-empty {
  display: inline-flex; align-items: center; justify-content: center;
  height: 16px;
  border: none; color: transparent; font-size: 0; line-height: 0; background: transparent;
}
.param-check.is-empty::before {
  content: '';
  width: 8px; height: 8px;
  border: 1.5px solid #d1d5db; border-radius: 50%;
  box-sizing: border-box;
}
.param-item.selected .param-check.is-empty::before { border-color: #93c5fd; }

.param-model {
  flex: 0 1 auto; min-width: 0; max-width: 46%;
  font-size: 11px; line-height: 16px; color: #6b7280;
  background: #f3f4f6; border-radius: 4px; padding: 0 5px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: default;
}
.param-item.selected .param-model {
  background: #dbeafe; color: #1e40af;
}
</style>
