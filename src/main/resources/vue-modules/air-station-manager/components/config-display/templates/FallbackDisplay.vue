<template>
  <div class="tpl">
    <p class="hint">未能识别连接类型，已按字段释义展示可用项。</p>
    <FieldRows v-if="commRows.length" :rows="commRows" />
    <p v-if="!commRows.length" class="empty">暂无可展示字段</p>
  </div>
</template>

<script setup>
defineOptions({ name: 'ConfigFallbackDisplay' })
import { computed } from 'vue'
import FieldRows from '../shared/FieldRows.vue'
import { SKIP_TOP_KEYS } from '../../comm/labels.js'
import { toFieldRows } from '../../comm/formatters.js'

const props = defineProps({
  resolved: { type: Object, required: true },
})

const commRows = computed(() => {
  const comm = props.resolved.comm || {}
  // 扁平可展示键；嵌套对象留给原始 JSON
  return toFieldRows(comm, { skipKeys: SKIP_TOP_KEYS })
})
</script>

<style scoped>
.tpl { min-width: 0; }
.hint { margin: 0 0 8px; font-size: 12px; color: #9ca3af; }
.empty { margin: 0; font-size: 13px; color: #9ca3af; }
</style>
