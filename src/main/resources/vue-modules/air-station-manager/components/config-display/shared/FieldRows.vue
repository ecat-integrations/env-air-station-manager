<template>
  <div class="field-rows">
    <div v-for="row in rows" :key="row.key" class="info-row">
      <span class="info-k">{{ row.label }}</span>
      <span class="info-v">{{ row.value }}</span>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ConfigFieldRows' })
defineProps({
  rows: { type: Array, default: () => [] },
})
</script>

<style scoped>
.field-rows { min-width: 0; }
.info-row { display: flex; font-size: 13px; line-height: 22px; }
.info-row + .info-row { margin-top: 2px; }
.info-k { color: #6b7280; width: 96px; flex-shrink: 0; }
.info-v {
  color: #111827; font-weight: 400; min-width: 0; word-break: break-all;
}
</style>
