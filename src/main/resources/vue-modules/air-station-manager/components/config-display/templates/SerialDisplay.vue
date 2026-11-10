<template>
  <div class="tpl">
    <p v-if="summary" class="summary">{{ summary }}</p>
    <SerialFields :serial="resolved.serial" />
    <FieldRows v-if="extraRows.length" :rows="extraRows" />
  </div>
</template>

<script setup>
defineOptions({ name: 'ConfigSerialDisplay' })
import { computed } from 'vue'
import SerialFields from '../shared/SerialFields.vue'
import FieldRows from '../shared/FieldRows.vue'
import { connectionSummary, toFieldRows } from '../../comm/formatters.js'

const props = defineProps({
  resolved: { type: Object, required: true },
})

const summary = computed(() => connectionSummary(props.resolved))

/** 串口块外的业务字段（如 station_id / channel / device_protocol） */
const extraRows = computed(() => {
  const comm = props.resolved.comm || {}
  const keys = ['device_protocol', 'station_id', 'channel']
  return toFieldRows(comm, { keys })
})
</script>

<style scoped>
.tpl { min-width: 0; }
/* 与设备型号同款（非等宽 + 600），避免 8O1 在等宽里看成 801 */
.summary {
  margin: 0 0 8px; font-size: 13px; font-weight: 600; color: #111827;
  font-family: inherit;
}
</style>
