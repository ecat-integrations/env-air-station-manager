<template>
  <!--
    站房设备总览（路由 name=monitor）：轮询 GET /asm-monitor/snapshot 渲染每台站房逻辑设备的
    全属性当前态卡片（live 优先/raw 回放兜，source 徽标区分）。本阶段不做 SSE，30s 轮询。
  -->
  <div class="asm-page">
    <div class="asm-toolbar">
      <span class="asm-title">站房设备总览</span>
      <span class="asm-hint">每 30 秒自动刷新；LIVE=总线实时态，RAW=最新采样回放</span>
      <button class="asm-btn" :disabled="loading" @click="load">手动刷新</button>
    </div>

    <div v-loading="loading" class="asm-cards">
      <div v-if="!loading && !devices.length" class="asm-empty">暂无站房设备（未创建或未绑定 logicdevice_station 设备）</div>
      <div v-for="d in devices" :key="d.logicDeviceUniqueId" class="asm-card">
        <div class="asm-card-head">
          <span class="asm-card-title">{{ d.logicDeviceUniqueId }}</span>
          <span class="asm-badge">{{ d.attrs.length }} 参数</span>
        </div>
        <table class="asm-table">
          <thead>
            <tr><th>参数</th><th>当前值</th><th>单位</th><th>更新时间</th><th>来源</th></tr>
          </thead>
          <tbody>
            <tr v-for="a in d.attrs" :key="a.attrId">
              <td>{{ a.attrId }}</td>
              <td>{{ displayValue(a) }}</td>
              <td>{{ a.unit || '-' }}</td>
              <td>{{ formatLocalDateTime(a.updateTime) }}</td>
              <td><span class="asm-badge" :class="a.source === 'LIVE' ? 'ok' : 'raw'">{{ a.source }}</span></td>
            </tr>
            <tr v-if="!d.attrs.length"><td colspan="5" class="asm-empty">该设备暂无属性数据</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（monitor），宿主 keep-alive 按组件名匹配缓存。

import { getSnapshot } from '@/api/asm'
import { formatLocalDateTime } from '@/utils/datetime'

const POLL_MS = 30000

export default {
  name: 'monitor',
  data() {
    return { devices: [], loading: false, timer: null }
  },
  mounted() {
    this.load()
    this.timer = setInterval(this.load, POLL_MS)
  },
  beforeUnmount() {
    if (this.timer) clearInterval(this.timer)
  },
  methods: {
    formatLocalDateTime,
    displayValue(a) {
      if (a.valueText != null && a.valueText !== '') return a.valueText
      return a.value == null ? '-' : a.value
    },
    async load() {
      this.loading = true
      try {
        const res = await getSnapshot()
        this.devices = (res && res.data) || []
      } finally {
        this.loading = false
      }
    },
  },
}
</script>

<style scoped>
.asm-page { padding: 12px; }
.asm-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.asm-title { font-size: 16px; font-weight: 600; }
.asm-hint { color: #909399; font-size: 12px; }
.asm-btn { padding: 4px 14px; border: 1px solid #dcdfe6; border-radius: 4px; background: #409eff; color: #fff; cursor: pointer; }
.asm-btn:disabled { opacity: .6; }
.asm-cards { min-height: 120px; }
.asm-card { border: 1px solid #ebeef5; border-radius: 6px; margin-bottom: 16px; padding: 8px 12px; }
.asm-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.asm-card-title { font-weight: 600; }
.asm-badge { font-size: 12px; padding: 1px 8px; border-radius: 10px; background: #f0f2f5; color: #606266; }
.asm-badge.ok { background: #f0f9eb; color: #67c23a; }
.asm-badge.raw { background: #fdf6ec; color: #e6a23c; }
.asm-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.asm-table th, .asm-table td { border-bottom: 1px solid #ebeef5; padding: 6px 8px; text-align: left; }
.asm-table th { background: #fafafa; color: #606266; }
.asm-empty { color: #909399; padding: 12px; text-align: center; }
</style>
