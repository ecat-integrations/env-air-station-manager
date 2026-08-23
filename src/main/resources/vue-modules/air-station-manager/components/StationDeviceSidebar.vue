<template>
  <!-- 左 sidebar：37 类型槽分组全景（镜像 ADM ParamSidebar，无 NOx 归并——ASM 槽各自独立）。
       纯展示组件：数据经 props 传入，选中经 emit 上报，不持业务状态、不调 API。 -->
  <aside class="sidebar">
    <div v-for="g in groups" :key="g.name" class="param-group">
      <div class="group-title">{{ g.name }}<span class="group-count">{{ g.items.length }}</span></div>
      <ul class="param-list">
        <li
          v-for="p in g.items"
          :key="p.param"
          class="param-item"
          :class="{ selected: selectedParam === p.param }"
          @click="$emit('select', p)"
        >
          <span class="param-label">{{ labelOf(p.param) }}</span>
          <span v-if="p.configured" class="param-check" title="已配置">✓</span>
        </li>
      </ul>
    </div>
  </aside>
</template>

<script setup>
defineOptions({ name: 'StationDeviceSidebar' })
import { labelOf } from '../stationParamMeta'

// groups：分组+items，由 composable 计算传入；selectedParam：当前选中槽的枚举名
defineProps({
  groups: { type: Array, required: true },
  selectedParam: { type: String, default: null },
})
defineEmits(['select'])
</script>

<style scoped>
/* sidebar：sticky 钉顶 + 自身纵向滚（37 项超一屏时组内滚，内容不裁）。 */
.sidebar {
  flex: 0 0 22%; min-width: 280px; max-width: 340px;
  position: sticky; top: 16px;
  max-height: calc(100vh - 140px); overflow-y: auto; overflow-x: hidden;
}
.param-group { margin-bottom: 8px; }
.group-title {
  font-size: 12px; font-weight: 600; color: #6b7280; text-transform: uppercase;
  letter-spacing: .5px; padding: 3px 8px; border-bottom: 1px solid #e5e7eb; margin-bottom: 2px;
  position: sticky; top: 0; background: #fff; z-index: 1;
}
.group-count { margin-left: 6px; color: #9ca3af; font-weight: 400; }
.param-list { list-style: none; margin: 0; padding: 0; }
.param-item {
  display: flex; align-items: center; gap: 8px;
  padding: 3px 10px; cursor: pointer; border-left: 3px solid transparent;
  border-radius: 0 4px 4px 0; font-size: 13px; line-height: 22px;
}
.param-item:hover { background: #f3f4f6; }
.param-item.selected { background: #eff6ff; border-left-color: #2563eb; }
.param-label { flex: 1; }
.param-check { color: #10b981; font-weight: 700; }
</style>
