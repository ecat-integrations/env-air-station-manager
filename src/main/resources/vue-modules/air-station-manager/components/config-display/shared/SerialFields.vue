<template>
  <FieldRows :rows="rows" />
</template>

<script setup>
defineOptions({ name: 'ConfigSerialFields' })
import { computed } from 'vue'
import FieldRows from './FieldRows.vue'
import { SERIAL_FIELD_ORDER, toFieldRows } from '../../comm/formatters.js'

const props = defineProps({
  serial: { type: Object, default: () => ({}) },
})

const rows = computed(() => toFieldRows(props.serial || {}, { keys: SERIAL_FIELD_ORDER }))
</script>
