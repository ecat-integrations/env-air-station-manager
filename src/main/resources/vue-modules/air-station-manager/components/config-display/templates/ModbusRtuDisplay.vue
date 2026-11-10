<template>
  <div class="tpl">
    <p v-if="summary" class="summary">{{ summary }}</p>
    <SerialFields :serial="resolved.serial" />
    <FieldRows :rows="modbusRows" />
  </div>
</template>

<script setup>
defineOptions({ name: 'ConfigModbusRtuDisplay' })
import { computed } from 'vue'
import SerialFields from '../shared/SerialFields.vue'
import FieldRows from '../shared/FieldRows.vue'
import { connectionSummary, toFieldRows } from '../../comm/formatters.js'

const props = defineProps({
  resolved: { type: Object, required: true },
})

const summary = computed(() => connectionSummary(props.resolved))

const modbusRows = computed(() => toFieldRows(props.resolved.comm || {}, {
  keys: ['slave_id', 'modbus_protocol'],
}))
</script>

<style scoped>
.tpl { min-width: 0; }
.summary {
  margin: 0 0 8px; font-size: 13px; font-weight: 600; color: #111827;
  font-family: inherit;
}
</style>
