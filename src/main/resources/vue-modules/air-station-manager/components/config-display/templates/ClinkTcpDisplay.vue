<template>
  <div class="tpl">
    <p v-if="summary" class="summary">{{ summary }}</p>
    <FieldRows :rows="rows" />
  </div>
</template>

<script setup>
defineOptions({ name: 'ConfigClinkTcpDisplay' })
import { computed } from 'vue'
import FieldRows from '../shared/FieldRows.vue'
import { connectionSummary, toFieldRows } from '../../comm/formatters.js'

const props = defineProps({
  resolved: { type: Object, required: true },
})

const summary = computed(() => connectionSummary(props.resolved))

const rows = computed(() => toFieldRows(props.resolved.comm || {}, {
  keys: ['host', 'port', 'conn_timeout', 'read_timeout'],
}))
</script>

<style scoped>
.tpl { min-width: 0; }
.summary {
  margin: 0 0 8px; font-size: 13px; font-weight: 600; color: #111827;
  font-family: inherit;
}
</style>
