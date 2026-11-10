<template>
  <!-- 配置结果友好展示：详细配置 / 原始配置 两个面板互斥展开。
       任一方展开时另一方只保留一行头部（始终可点击），展开区超出高度各自内部滚动。
       与 ADM ParamDetail 的 ConfigDisplay 同构：点已展开的面板 → 折叠它并展开另一方。 -->
  <div v-if="!data" class="cfg-loading">加载中…</div>
  <div v-else class="cfg-display">
    <!-- ① 详细配置（连接分项 + 其他部署参数） -->
    <section class="pane" :class="{ 'is-open': activePane === 'conn' }">
      <header class="pane-head" @click="activate('conn')">
        <span class="pane-arrow">▸</span>
        <span class="pane-title">详细配置</span>
        <span class="pane-badge">{{ resolved.title }}</span>
        <span v-if="activePane !== 'conn' && summaryLine" class="pane-sum">{{ summaryLine }}</span>
      </header>
      <div v-if="activePane === 'conn'" class="pane-body">
        <component :is="tpl" :resolved="resolved" />
        <!-- 其他配置：与连接分项用一条淡虚线隔开，避免被当成连接参数 -->
        <template v-if="extraRows.length">
          <div class="extra-sep"><span class="extra-sep-txt">其他配置</span></div>
          <FieldRows :rows="extraRows" />
        </template>
      </div>
    </section>

    <!-- ② 原始配置（可复制：点击图标把整份 JSON 写入剪贴板，1.5s 后回到常态） -->
    <section class="pane" :class="{ 'is-open': activePane === 'raw' }">
      <header class="pane-head" @click="activate('raw')">
        <span class="pane-arrow">▸</span>
        <span class="pane-title">原始配置</span>
        <button
          type="button"
          class="raw-copy"
          :class="{ ok: copyOk }"
          :title="copyOk ? '已复制' : '复制 JSON'"
          aria-label="复制原始配置"
          @click.stop="copyRaw"
        >
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="9" y="9" width="11" height="11" rx="2" />
            <path d="M5 15V6a2 2 0 0 1 2-2h8" />
          </svg>
          <span v-if="copyOk" class="raw-copy-tip">已复制</span>
        </button>
      </header>
      <div v-if="activePane === 'raw'" class="pane-body">
        <pre class="raw-dump">{{ rawText }}</pre>
      </div>
    </section>
  </div>
</template>

<script setup>
defineOptions({ name: 'ConfigDisplay' })
import { computed, onBeforeUnmount, ref } from 'vue'
import { resolveConnectionType } from '../comm/resolveConnectionType.js'
import { connectionSummary } from '../comm/formatters.js'
import { extractExtraRows } from './shared/extraConfig.js'
import FieldRows from './shared/FieldRows.vue'
import { templateFor } from './registry.js'

const props = defineProps({
  /** entry.data 对象；null/undefined = 加载中 */
  data: { type: Object, default: null },
  /** { coordinate, deviceModel } 等，辅助识别 CLink 等 */
  hint: { type: Object, default: () => ({}) },
})

/** 互斥展开：同一时刻恰有一个面板展开，另一方保持一行头部可点击 */
const activePane = ref('conn')
const copyOk = ref(false)
let copyTimer = 0

const resolved = computed(() => resolveConnectionType(props.data, props.hint || {}))
const tpl = computed(() => templateFor(resolved.value.type))
const summaryLine = computed(() => connectionSummary(resolved.value))
/** 其他配置：序列号 / 采集间隔 等不属于连接参数的顶层标量 */
const extraRows = computed(() => extractExtraRows(props.data))
const rawText = computed(() => {
  try {
    return JSON.stringify(props.data || {}, null, 2)
  } catch (_) {
    return String(props.data)
  }
})

function activate(pane) {
  // 互斥联动：点到已展开的面板 → 该面板折叠、另一面板展开
  activePane.value = activePane.value === pane ? (pane === 'conn' ? 'raw' : 'conn') : pane
}

async function copyRaw() {
  const text = rawText.value
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      // 非安全上下文（http + 非 localhost）没有 clipboard API：回退到 execCommand
      const ta = document.createElement('textarea')
      ta.value = text
      ta.setAttribute('readonly', '')
      ta.style.cssText = 'position:fixed;left:-9999px;top:0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    copyOk.value = true
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => { copyOk.value = false }, 1500)
  } catch (_) {
    copyOk.value = false
  }
}

onBeforeUnmount(() => { if (copyTimer) clearTimeout(copyTimer) })
</script>

<style scoped>
.cfg-display {
  flex: 1 1 auto; min-height: 0;
  display: flex; flex-direction: column; gap: 8px;
  /* 总高度由父级给定；两个面板在此内部争抢，绝不外溢到按钮区 */
  overflow: hidden;
}

.cfg-loading {
  flex: 1; min-height: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; color: #9ca3af;
  background: #f9fafb; border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
}

/* 折叠态：只占头部一行；展开态：吃掉剩余高度并在内部滚动 */
.pane {
  flex: 0 0 auto;
  display: flex; flex-direction: column;
  min-height: 0;
  background: #f9fafb;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  overflow: hidden;
}
.pane.is-open {
  flex: 1 1 0;
  min-height: 72px;
}

.pane-head {
  flex: 0 0 auto;
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 12px;
  cursor: pointer; user-select: none;
}
.pane-head:hover { background: #f3f4f6; }
.pane.is-open .pane-head {
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}

.pane-arrow {
  display: inline-block; width: 1em;
  color: #9ca3af; font-size: 12px;
  transition: transform .12s;
}
.pane.is-open .pane-arrow { transform: rotate(90deg); }

.pane-title {
  font-size: 12px; color: #6b7280; flex-shrink: 0;
}
.pane-badge {
  font-size: 12px; font-weight: 500; color: #2563eb;
  background: #eff6ff; padding: 2px 8px; border-radius: 10px; flex-shrink: 0;
}
/* 折叠时的一行摘要：与设备型号同款（非等宽加粗，O/0 更易辨） */
.pane-sum {
  margin-left: auto;
  font-size: 13px; font-weight: 600; color: #111827;
  font-family: inherit;
  min-width: 0; word-break: break-all;
}

.pane-body {
  flex: 1 1 auto; min-height: 0;
  overflow: auto;
  padding: 10px 12px;
  display: flex; flex-direction: column;
}

/* 其他配置的淡虚线分隔：只做「同类内分区」提示，不抢连接分项的层级 */
.extra-sep {
  flex: 0 0 auto;
  display: flex; align-items: center; gap: 8px;
  margin: 12px 0 8px;
}
.extra-sep-txt {
  font-size: 11px; color: #9ca3af; flex-shrink: 0;
}
.extra-sep::after {
  content: ''; flex: 1 1 auto;
  border-top: 1px dashed #e5e7eb;
}

/* 紧挨「原始配置」文字后的复制图标，小空隙即可 */
.raw-copy {
  display: inline-flex; align-items: center; justify-content: center;
  margin-left: 6px;
  padding: 2px; border: 0; border-radius: 4px;
  background: transparent; color: #9ca3af;
  cursor: pointer; line-height: 0;
  transition: color .12s, background .12s;
}
.raw-copy:hover { color: #374151; background: #f3f4f6; }
.raw-copy.ok { color: #059669; background: #ecfdf5; }
/* 图标后的短暂「已复制」提示 */
.raw-copy-tip {
  margin-left: 4px;
  font-size: 12px; line-height: 1;
  color: #059669;
  white-space: nowrap;
}

.raw-dump {
  flex: 0 0 auto;
  margin: 0;
  font-size: 12px; white-space: pre-wrap; word-break: break-all;
  color: #374151;
}
</style>
