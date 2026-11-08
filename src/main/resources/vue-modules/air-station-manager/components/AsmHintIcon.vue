<template>
  <button
    ref="btnRef"
    type="button"
    class="asm-hint"
    :aria-label="label"
    @click.stop="onPeek"
    @mouseenter="onShow"
    @mouseleave="onHide"
  >
    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="10" />
      <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
      <path d="M12 17h.01" />
    </svg>
  </button>
  <Teleport to="body">
    <span
      v-if="visible"
      class="asm-hint-pop"
      :class="{ 'is-leaving': leaving }"
      :style="popStyle"
    >{{ text }}</span>
  </Teleport>
</template>

<script setup>
defineOptions({ name: 'AsmHintIcon' })
import { onBeforeUnmount, ref } from 'vue'

const PEEK_HOLD_MS = 1500
const PEEK_FADE_MS = 280

defineProps({
  text: { type: String, required: true },
  label: { type: String, default: '说明' },
})

const btnRef = ref(null)
const visible = ref(false)
const leaving = ref(false)
const popStyle = ref({})
let holdTimer = 0
let fadeTimer = 0

function clearTimers() {
  window.clearTimeout(holdTimer)
  window.clearTimeout(fadeTimer)
  holdTimer = 0
  fadeTimer = 0
}

function hideNow() {
  clearTimers()
  visible.value = false
  leaving.value = false
}

function showPop() {
  const el = btnRef.value
  if (el) {
    const r = el.getBoundingClientRect()
    const showAbove = r.top > 40
    popStyle.value = {
      position: 'fixed',
      top: `${Math.round(showAbove ? r.top - 6 : r.bottom + 6)}px`,
      left: `${Math.round(r.left + r.width / 2)}px`,
      transform: showAbove ? 'translate(-50%, -100%)' : 'translate(-50%, 0)',
      zIndex: 4000,
    }
  }
  clearTimers()
  leaving.value = false
  visible.value = true
}

function onPeek() {
  showPop()
  holdTimer = window.setTimeout(() => {
    leaving.value = true
    fadeTimer = window.setTimeout(hideNow, PEEK_FADE_MS)
  }, PEEK_HOLD_MS)
}

function onShow() {
  showPop()
}

function onHide() {
  if (!visible.value) return
  clearTimers()
  leaving.value = true
  fadeTimer = window.setTimeout(hideNow, PEEK_FADE_MS)
}

onBeforeUnmount(hideNow)
</script>

<style scoped>
.asm-hint {
  position: relative;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  border: none;
  background: none;
  color: #9ca3af;
  cursor: pointer;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}
.asm-hint:hover { color: var(--el-color-primary, #409eff); background: #eff6ff; }
</style>

<style>
.asm-hint-pop {
  padding: 6px 10px;
  border-radius: 6px;
  background: #111827;
  color: #fff;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  white-space: normal;
  max-width: 360px;
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.22);
  pointer-events: none;
  opacity: 1;
  transition: opacity 0.28s ease;
}
.asm-hint-pop.is-leaving { opacity: 0; }
</style>
