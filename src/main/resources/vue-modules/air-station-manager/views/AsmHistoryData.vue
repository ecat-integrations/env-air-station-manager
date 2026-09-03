<template>
  <!--
    历史数据（路由 name=history_data）：粒度/时间窗/参数多选 → GET /asm-monitor/history（扁平行集），
    前端按 uid:attrId 分组成 series：echarts 折线（值）+ 明细表（validCount/totalCount）。
    参数候选池来自 GET /asm-monitor/stat-params（与 Java SDK listStatParams 同源；
    按 applicableGranularityMask 过滤当前粒度不可物化的参数）。
    查询区/明细表 element-plus 化（el-form inline + el-radio-group + el-date-picker + el-table，
    宿主全局注册免 import）；echarts 图表保留。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">历史数据</span></div>

    <div class="asm-filter">
      <!--
        查询区 element-plus 化（宿主全局注册免 import）：粒度/区间/单位 → el-radio-group(button)，
        时间 → el-date-picker(datetimerange)。时间线上格式逐字节不变：value-format
        'YYYY-MM-DDTHH:mm:ss'（含 T 含秒壁钟串，后端按原 datetime-local 同串解析）；
        range 双端数组经 timeRange computed 拆回 filter.start/filter.end，提交代码路径零改动。
        参数多选池保持手写 checkbox（非单行表单控件），占满整行的 el-form-item 承载。
      -->
      <el-form :inline="true" size="small" @submit.prevent>
        <el-form-item label="粒度">
          <el-radio-group v-model="filter.granularity" size="small" @change="search">
            <el-radio-button v-for="g in GRANULARITY" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="区间">
          <el-radio-group v-model="filter.mode" size="small" @change="search">
            <el-radio-button v-for="m in MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="单位">
          <el-radio-group v-model="filter.unit" size="small" @change="search">
            <el-radio-button v-for="u in UNITS" :key="u.value" :value="u.value">{{ u.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            range-separator="~"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :clearable="false"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        </el-form-item>
        <el-form-item label="参数" class="asm-params-item">
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
        </el-form-item>
      </el-form>
    </div>

    <div v-loading="loading" class="asm-chart-wrap">
      <div ref="chartEl" class="asm-chart"></div>
      <div v-if="!loading && !series.length" class="asm-empty">无数据（选择参数后查询）</div>
    </div>

    <el-table v-if="series.length" :data="tableRows" size="small">
      <el-table-column label="时刻" min-width="160">
        <template #default="{ row }">{{ formatLocalDateTime(row.dataTime) }}</template>
      </el-table-column>
      <el-table-column
        v-for="s in series"
        :key="s.key"
        :label="s.key + (s.unit ? ' (' + s.unit + ')' : '')"
        min-width="140"
      >
        <template #default="{ row }">{{ row.cells[s.key] == null ? '--' : row.cells[s.key] }}</template>
      </el-table-column>
    </el-table>
    <div class="asm-pager" v-if="series.length">
      <el-button size="small" :disabled="filter.pageNum <= 1 || loading" @click="turn(-1)">上一页</el-button>
      <span>第 {{ filter.pageNum }} 页</span>
      <el-button size="small" :disabled="!hasNext || loading" @click="turn(1)">下一页</el-button>
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
    // el-date-picker(datetimerange) 双端数组 ↔ filter.start/end 单值串的桥；
    // 提交侧仍读 filter.start/filter.end（线上格式由 value-format 逐字节保证，queryHistory 调用点零改动）。
    timeRange: {
      get() {
        return [this.filter.start, this.filter.end]
      },
      set([start, end]) {
        this.filter.start = start
        this.filter.end = end
      },
    },
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
/* 参数多选池占满整行（inline form 的 form-item 默认按内容收缩，checkbox 群需要整行才能 flex-wrap） */
.asm-params-item { width: 100%; }
.asm-params-item :deep(.el-form-item__label) { line-height: 26px; }
.asm-radio { font-size: 13px; display: inline-flex; align-items: center; gap: 4px; }
.asm-params { display: flex; flex-wrap: wrap; gap: 8px 20px; min-height: 28px; flex: 1; }
.asm-param-group .asm-param-head { font-weight: 600; font-size: 13px; margin-bottom: 2px; }
.asm-chart-wrap { position: relative; border: 1px solid #ebeef5; border-radius: 6px; margin-bottom: 12px; }
.asm-chart { width: 100%; height: 360px; }
.asm-empty, .asm-empty-inline { color: #909399; padding: 8px; text-align: center; }
.asm-pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; color: #606266; }
</style>
