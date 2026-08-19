<template>
  <!--
    历史数据（路由 name=history_data）：粒度/时间窗/参数多选 → GET /asm-monitor/history（扁平行集），
    前端按 uid:attrId 分组成 series：echarts 折线（值）+ 明细表（validCount/totalCount）。
    参数候选池来自 GET /asm-monitor/stat-params（与 Java SDK listStatParams 同源；
    按 applicableGranularityMask 过滤当前粒度不可物化的参数）。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">历史数据</span></div>

    <div class="asm-filter">
      <div class="asm-row">
        <span class="asm-label">粒度</span>
        <label v-for="g in GRANULARITY" :key="g.value" class="asm-radio">
          <input type="radio" name="asm-g" :value="g.value" v-model="filter.granularity" @change="search" />{{ g.label }}
        </label>
        <span class="asm-label" style="margin-left:16px">区间</span>
        <label v-for="m in MODES" :key="m.value" class="asm-radio">
          <input type="radio" name="asm-m" :value="m.value" v-model="filter.mode" @change="search" />{{ m.label }}
        </label>
        <span class="asm-label" style="margin-left:16px">单位</span>
        <label v-for="u in UNITS" :key="u.value" class="asm-radio">
          <input type="radio" name="asm-u" :value="u.value" v-model="filter.unit" @change="search" />{{ u.label }}
        </label>
      </div>
      <div class="asm-row">
        <span class="asm-label">时间</span>
        <input type="datetime-local" step="1" v-model="filter.start" />
        <span>~</span>
        <input type="datetime-local" step="1" v-model="filter.end" />
        <button class="asm-btn primary" :disabled="loading" @click="search">查询</button>
      </div>
      <div class="asm-row">
        <span class="asm-label">参数</span>
        <div v-loading="metaLoading" class="asm-params">
          <div v-for="d in devices" :key="d.logicDeviceUniqueId" class="asm-param-group">
            <div class="asm-param-head">{{ d.logicDeviceUniqueId }}</div>
            <label v-for="a in applicableAttrs(d)" :key="a.attrId" class="asm-radio">
              <input
                type="checkbox"
                :value="d.logicDeviceUniqueId + ':' + a.attrId"
                v-model="checked"
              />
              {{ a.paramDisplayName }}{{ a.storageUnit ? ' (' + a.storageUnit + ')' : '' }}
            </label>
          </div>
          <div v-if="!devices.length && !metaLoading" class="asm-empty-inline">暂无可查参数（参数候选来自 stat-params，需先建聚合配置）</div>
        </div>
      </div>
    </div>

    <div v-loading="loading" class="asm-chart-wrap">
      <div ref="chartEl" class="asm-chart"></div>
      <div v-if="!loading && !series.length" class="asm-empty">无数据（选择参数后查询）</div>
    </div>

    <table class="asm-table" v-if="series.length">
      <thead>
        <tr><th>时刻</th><th v-for="s in series" :key="s.key">{{ s.key }} {{ s.unit ? '(' + s.unit + ')' : '' }}</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in tableRows" :key="r.dataTime">
          <td>{{ formatLocalDateTime(r.dataTime) }}</td>
          <td v-for="s in series" :key="s.key">{{ r.cells[s.key] == null ? '--' : r.cells[s.key] }}</td>
        </tr>
      </tbody>
    </table>
    <div class="asm-pager" v-if="series.length">
      <button class="asm-btn" :disabled="filter.pageNum <= 1 || loading" @click="turn(-1)">上一页</button>
      <span>第 {{ filter.pageNum }} 页</span>
      <button class="asm-btn" :disabled="!hasNext || loading" @click="turn(1)">下一页</button>
    </div>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（history_data），宿主 keep-alive 按组件名匹配缓存。

import * as echarts from 'echarts'
import { queryHistory, listStatParams } from '@/api/asm'
import { formatLocalDateTime, formatLocalInputSeconds } from '@/utils/datetime'

// 粒度 → applicableGranularityMask 位（与后端 AsmGranularityMask 同定义：bit0=minute/bit1=5min/bit2=hour）
const GRANULARITY_BIT = { MINUTE: 1, FIVE_MIN: 2, HOUR: 4 }

const GRANULARITY = [
  { value: 'MINUTE', label: '分钟' },
  { value: 'FIVE_MIN', label: '5分钟' },
  { value: 'HOUR', label: '小时' },
]
const MODES = [
  { value: 'BACK', label: '后标 (L,R]' },
  { value: 'FRONT', label: '前标 [S,E)' },
]
const UNITS = [
  { value: 'custom', label: '自定义' },
  { value: 'standard', label: '标准' },
]

export default {
  name: 'history_data',
  data() {
    const now = new Date()
    return {
      GRANULARITY, MODES, UNITS,
      filter: {
        granularity: 'HOUR',
        mode: 'BACK',
        unit: 'custom',
        start: formatLocalInputSeconds(new Date(now.getTime() - 24 * 3600 * 1000)),
        end: formatLocalInputSeconds(now),
        pageNum: 1,
        pageSize: 200,
      },
      checked: [],
      devices: [],
      metaLoading: false,
      loading: false,
      rows: [],
      chart: null,
    }
  },
  computed: {
    series() {
      // 扁平行集按 uid:attrId 分组（取每 series 首个非空 unit 作图例单位）
      const map = new Map()
      for (const r of this.rows) {
        const key = r.logicDeviceUniqueId + ':' + r.attrId
        if (!map.has(key)) map.set(key, { key, unit: r.unit, points: [] })
        map.get(key).points.push(r)
      }
      return [...map.values()]
    },
    tableRows() {
      // 桶时刻并集 → 行 cells {key→value}
      const byTime = new Map()
      for (const s of this.series) {
        for (const p of s.points) {
          if (!byTime.has(p.dataTime)) byTime.set(p.dataTime, { dataTime: p.dataTime, cells: {} })
          byTime.get(p.dataTime).cells[s.key] = p.value
        }
      }
      return [...byTime.values()].sort((a, b) => (a.dataTime < b.dataTime ? -1 : 1))
    },
    hasNext() {
      return this.rows.length >= this.filter.pageSize
    },
  },
  mounted() {
    this.loadMeta()
    this.chart = echarts.init(this.$refs.chartEl)
    window.addEventListener('resize', this.resize)
  },
  beforeUnmount() {
    window.removeEventListener('resize', this.resize)
    if (this.chart) this.chart.dispose()
  },
  methods: {
    formatLocalDateTime,
    // 当前粒度位过滤（mask 位未开=该粒度不物化，查了也空）
    applicableAttrs(d) {
      const bit = GRANULARITY_BIT[this.filter.granularity]
      return d.attrs.filter((a) => ((a.applicableGranularityMask || 0) & bit) !== 0)
    },
    resize() {
      if (this.chart) this.chart.resize()
    },
    async loadMeta() {
      this.metaLoading = true
      try {
        // stat-params（SDK 同源）：只列已建聚合配置的参数（有 stat 桶可查），displayName 由后端投影
        const res = await listStatParams()
        const metas = (res && res.data) || []
        const map = new Map()
        for (const m of metas) {
          if (!map.has(m.logicDeviceUniqueId)) map.set(m.logicDeviceUniqueId, { logicDeviceUniqueId: m.logicDeviceUniqueId, attrs: [] })
          map.get(m.logicDeviceUniqueId).attrs.push(m)
        }
        this.devices = [...map.values()]
      } finally {
        this.metaLoading = false
      }
    },
    turn(delta) {
      this.filter.pageNum += delta
      this.search()
    },
    async search() {
      if (!this.checked.length) {
        this.rows = []
        this.renderChart()
        return
      }
      this.loading = true
      try {
        const res = await queryHistory({
          granularity: this.filter.granularity,
          start: this.filter.start,
          end: this.filter.end,
          params: this.checked.join(','),
          mode: this.filter.mode,
          unit: this.filter.unit,
          pageNum: this.filter.pageNum,
          pageSize: this.filter.pageSize,
        })
        this.rows = (res && res.data && res.data.rows) || []
        this.renderChart()
      } finally {
        this.loading = false
      }
    },
    renderChart() {
      if (!this.chart) return
      const times = this.tableRows.map((r) => formatLocalDateTime(r.dataTime, true))
      this.chart.clear()
      this.chart.setOption({
        tooltip: { trigger: 'axis' },
        legend: { type: 'scroll', bottom: 0 },
        grid: { left: 56, right: 24, top: 24, bottom: 40 },
        xAxis: { type: 'category', data: times },
        yAxis: { type: 'value', scale: true },
        series: this.series.map((s) => ({
          name: s.key,
          type: 'line',
          showSymbol: false,
          connectNulls: true,
          data: this.tableRows.map((r) => r.cells[s.key]),
        })),
      })
    },
  },
}
</script>

<style scoped>
.asm-page { padding: 12px; }
.asm-toolbar { margin-bottom: 8px; }
.asm-title { font-size: 16px; font-weight: 600; }
.asm-filter { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px; margin-bottom: 12px; }
.asm-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.asm-row:last-child { margin-bottom: 0; }
.asm-label { color: #606266; font-size: 13px; }
.asm-radio { font-size: 13px; display: inline-flex; align-items: center; gap: 4px; }
.asm-btn { padding: 4px 14px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; cursor: pointer; }
.asm-btn.primary { background: #409eff; color: #fff; }
.asm-btn:disabled { opacity: .6; }
.asm-params { display: flex; flex-wrap: wrap; gap: 8px 20px; min-height: 28px; flex: 1; }
.asm-param-group .asm-param-head { font-weight: 600; font-size: 13px; margin-bottom: 2px; }
.asm-chart-wrap { position: relative; border: 1px solid #ebeef5; border-radius: 6px; margin-bottom: 12px; }
.asm-chart { width: 100%; height: 360px; }
.asm-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.asm-table th, .asm-table td { border-bottom: 1px solid #ebeef5; padding: 6px 8px; text-align: left; white-space: nowrap; }
.asm-table th { background: #fafafa; color: #606266; }
.asm-empty, .asm-empty-inline { color: #909399; padding: 8px; text-align: center; }
.asm-pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; color: #606266; }
</style>
