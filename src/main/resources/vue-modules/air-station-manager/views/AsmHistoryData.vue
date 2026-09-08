<template>
  <!--
    历史数据（路由 name=history_data）：ruoyi 原生形态——el-form :inline 搜索区（粒度/时间窗/区间/单位/
    参数触发）+ mb8 操作行（导出/视图切换）+ 结果主体（列表/曲线）占满剩余视口（页面级不滚动，表格内部滚）。
    参数选择走 dialog：搜索区只读输入框回显「首项中文 等 N 项」单行，点开弹窗内勾选（设备分组+组级全选+
    搜索过滤），确定才回填勾选并重查。
    列契约：表格/导出列头 = 勾选集全集（checkedKeys 顺序），无数据参数照常占列、单元格 '--'；
    当前粒度不可物化的参数查询时跳过（提交集=可物化子集）但列头保留，悬浮「当前粒度不物化」。
    查询流：GET /asm-monitor/history（扁平行集）→ 前端按 uid:attrId 分组透视为宽表；分页用「下一页探测」
    （后端现无 total，当前页行数==pageSize 则认为有下一页）；导出=循环分页拉全量生成 CSV。
    参数候选池来自 GET /asm-monitor/stat-params（与 Java SDK listStatParams 同源；
    按 applicableGranularityMask 置灰当前粒度不可物化的参数，勾选态保留但查询时跳过）。
    契约字段（后端并行追加，缺失回退）：stat-params 行 display_unit（缺→storageUnit）/
    device_label（缺→uniqueId）；history 行 display_unit（缺→unit）。
  -->
  <div class="asm-page asm-history">
    <!-- 搜索区：ruoyi 原生 el-form :inline 平铺；@submit.prevent 阻原生隐式提交（Enter 整页刷新） -->
    <el-form class="asm-filter" :inline="true" @submit.prevent>
      <el-form-item label="粒度">
        <el-radio-group v-model="filter.granularity" @change="search">
          <el-radio-button v-for="g in GRANULARITY" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="时间">
        <!--
          时间线上格式逐字节不变：value-format 'YYYY-MM-DDTHH:mm:ss'（含 T 含秒壁钟串，
          后端按原 datetime-local 同串解析）；range 双端数组经 timeRange computed 拆回
          filter.start/filter.end，提交代码路径零改动。默认窗=近 1 小时。
        -->
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          range-separator="~"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :clearable="false"
          style="width: 360px"
        />
      </el-form-item>
      <el-form-item label="区间">
        <el-radio-group v-model="filter.mode" @change="search">
          <el-tooltip v-for="m in MODES" :key="m.value" :content="MODE_HINTS[m.value]" placement="top">
            <el-radio-button :value="m.value">{{ m.label }}</el-radio-button>
          </el-tooltip>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="单位">
        <el-radio-group v-model="filter.unit" @change="search">
          <el-radio-button v-for="u in UNITS" :key="u.value" :value="u.value">{{ u.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="参数">
        <!--
          只读触发框（ruoyi 树选择形态）：回显单行「首项中文 等 N 项」（单项直接显示），完整清单在弹窗/
          表格列头看。@click 经 attrs 落到内层 input（element-plus inheritAttrs:false），点输入区即弹窗；
          suffix 手工放清除（checked 非空才显，el-input readonly 下原生 clearable 图标不渲染）+ 下拉箭头。
        -->
        <el-input
          :model-value="paramSummaryText"
          readonly
          placeholder="选择参数..."
          class="asm-param-input"
          @click="openParamDialog"
        >
          <template #suffix>
            <el-icon v-if="checked.length" class="asm-param-clear" title="清空已选参数" @click.stop="clearAllChecked"><CircleClose /></el-icon>
            <el-icon class="asm-param-arrow" @click="openParamDialog"><ArrowDown /></el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" :loading="loading" @click="search">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 操作行（ruoyi mb8 风格）：导出 + 单位及修约 | 右侧视图切换（列表/曲线；曲线态再切分图/合并） -->
    <el-row :gutter="10" class="mb8 asm-result-head">
      <el-col :span="1.5">
        <el-button type="warning" plain icon="Download" :loading="exporting" :disabled="!submittableKeys.length" @click="exportCsv">导出</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button plain icon="Setting" @click="openUnitDialog">单位及修约</el-button>
      </el-col>
      <el-col :span="20" class="asm-view-col">
        <el-radio-group v-model="viewMode">
          <el-radio-button value="list">列表</el-radio-button>
          <el-radio-button value="chart">曲线</el-radio-button>
        </el-radio-group>
        <template v-if="viewMode === 'chart'">
          <el-radio-group v-model="chartLayout" class="asm-chart-layout">
            <el-radio-button value="split">分图</el-radio-button>
            <el-radio-button value="merge">合并</el-radio-button>
          </el-radio-group>
          <span v-if="sameUnit" class="asm-merge-hint">单位相同可合并</span>
        </template>
      </el-col>
    </el-row>

    <el-alert v-if="errorMsg" type="error" :closable="true" :title="errorMsg" @close="errorMsg = ''" style="margin-bottom: 8px" />

    <div class="asm-result-body">
      <!-- 列表：v-if=series 非空（勾选集即列契约）——全窗无数据时表头仍完整、仅 0 数据行 -->
      <div v-show="viewMode === 'list'" v-loading="loading" class="asm-table-wrap">
        <el-table v-if="series.length" :data="tableRows" size="small" border height="100%">
          <el-table-column label="时刻" min-width="170" fixed="left">
            <template #default="{ row }">{{ formatLocalDateTime(row.dataTime) }}</template>
          </el-table-column>
          <el-table-column v-for="s in series" :key="s.key" min-width="150">
            <template #header>
              <!-- 不可物化列（当前粒度被提交集剔除、数据恒空）悬浮说明；可物化列不加 tooltip -->
              <el-tooltip :disabled="isSeriesMaterializable(s)" content="当前粒度不物化" placement="top">
                <div class="asm-col-head">
                  <div class="asm-col-name">{{ s.paramName }}</div>
                  <div class="asm-col-unit">{{ s.unit ? '(' + s.unit + ')' : '' }}</div>
                </div>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <template v-if="row.cells[s.key]">
                <span>{{ cellText(row.cells[s.key]) }}</span>
                <el-tooltip
                  v-if="partialValid(row.cells[s.key])"
                  :content="validHint(row.cells[s.key])"
                  placement="top"
                ><span class="asm-valid-dot">·</span></el-tooltip>
              </template>
              <span v-else class="asm-muted">--</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="无数据（选择参数后查询）" />
      </div>

      <div v-show="viewMode === 'chart'" v-loading="loading" class="asm-chart-wrap">
        <div ref="chartEl" class="asm-chart"></div>
        <div v-if="!series.length && !loading" class="asm-chart-empty">
          <el-empty description="无数据（选择参数后查询）" />
        </div>
      </div>
    </div>

    <!--
      分页（下一页探测）：后端 history 响应现无 total 字段——当前页行数==pageSize 判定有下一页，
      probedTotal 据此构造（有下一页时 total=已取行数+1，让 pager 放出下一页按钮）；
      后端补 total 后可改为直读。计数口径=扁平行数（数据点数），与表格时刻行数不同（多参数透视）。
    -->
    <div class="asm-pager" v-if="rows.length || filter.pageNum > 1">
      <!-- 计数口径=数据点数（扁平行，多参数同刻透视为一时刻行）——自写消歧文案，不用 el-pagination 内置 total（"共N条"与表格行数不符困惑） -->
      <span class="asm-pager-count">共 {{ probedTotal }} 个数据点</span>
      <el-pagination
        size="small"
        background
        layout="prev, pager, next"
        :page-size="filter.pageSize"
        :current-page="filter.pageNum"
        @current-change="turnPage"
      />
    </div>

    <!-- 参数选择弹窗：草稿勾选（dialogChecked），确定才回填 checked 并重查；取消丢弃 -->
    <el-dialog v-model="paramDialogVisible" title="选择参数" width="720px" append-to-body>
      <div v-loading="metaLoading" class="asm-params">
        <!-- 设备下拉搜索（filterable 可输入过滤选项）+ 参数关键字两段筛选：设备靠选不靠敲，减少输入 -->
        <div class="asm-param-filter-row">
          <el-select v-model="paramDeviceUid" filterable clearable placeholder="按设备筛选（可输入搜索）" size="small" class="asm-param-device">
            <el-option v-for="d in devices" :key="d.uid" :value="d.uid" :label="d.label" />
          </el-select>
          <el-input
            v-model="paramKeyword"
            clearable
            placeholder="按参数搜索"
            class="asm-param-search"
          />
        </div>
        <div class="asm-param-groups">
          <div v-for="d in filteredDevices" :key="d.uid" class="asm-param-group">
            <div class="asm-param-head">
              <el-checkbox
                :model-value="groupAllChecked(d)"
                :indeterminate="groupPartialChecked(d)"
                @change="toggleGroup(d, $event)"
              >{{ d.label }}</el-checkbox>
            </div>
            <el-checkbox-group v-model="dialogChecked" class="asm-param-attrs">
              <el-tooltip
                v-for="a in d.attrs"
                :key="a.attrId"
                :disabled="isAttrApplicable(a)"
                content="当前粒度不物化（勾选保留，查询时跳过）"
                placement="top"
              >
                <el-checkbox class="asm-param-cb" :value="d.uid + ':' + a.attrId" :disabled="!isAttrApplicable(a)">
                  {{ a.paramDisplayName || a.attrId }}{{ attrUnitLabel(a) ? ' (' + attrUnitLabel(a) + ')' : '' }}
                </el-checkbox>
              </el-tooltip>
            </el-checkbox-group>
          </div>
        </div>
        <div v-if="!devices.length && !metaLoading" class="asm-empty-inline">暂无可查参数（参数候选来自 stat-params，需先建聚合配置）</div>
        <div v-else-if="!filteredDevices.length && !metaLoading" class="asm-empty-inline">无匹配参数</div>
      </div>
      <template #footer>
        <el-button @click="paramDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="confirmParamDialog">确 定</el-button>
      </template>
    </el-dialog>

    <!-- 历史查询单位偏好（HISTORY purpose）：与配置页>单位偏好同源同端点（PUT config-unit），
         一处保存两处生效；编辑口径复用配置页单位 tab 行形态（设备选择 + 参数行单位下拉）。 -->
    <el-dialog v-model="unitDialogVisible" title="历史查询单位（HISTORY 偏好）" width="680px" append-to-body>
      <div v-loading="unitLoading">
        <el-alert v-if="unitError" type="error" :closable="false" :title="unitError" style="margin-bottom: 10px" />
        <el-select v-model="unitDeviceUid" filterable clearable placeholder="选择设备" size="small" style="width: 300px; margin-bottom: 10px">
          <el-option v-for="d in unitDevices" :key="d.uid" :value="d.uid" :label="d.label" />
        </el-select>
        <table v-if="unitRows.length" class="asm-unit-table">
          <thead>
            <tr><th>参数</th><th>历史查询单位</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in unitRows" :key="r.attrId">
              <td>{{ r.displayName }}</td>
              <td>
                <el-select v-if="r.unitOptions" v-model="r.historyUnit" size="small" style="width: 150px" @change="markUnitDirty(r)">
                  <el-option label="原生（不换算）" value="" />
                  <el-option-group v-for="g in r.unitOptions" :key="g.classLabel" :label="g.classLabel">
                    <el-option v-for="u in g.units" :key="u.key" :value="u.key" :label="u.symbol" />
                  </el-option-group>
                </el-select>
                <span v-else class="asm-muted">{{ r.unitSymbol || '-' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="!unitLoading" class="asm-empty-inline">{{ unitDeviceUid ? '该设备暂无可配单位的数值参数' : '选择设备后编辑其参数的历史查询单位' }}</div>
        <div class="asm-hint">
          单位候选与「配置页 &gt; 单位偏好」同源（一处保存两处生效）。历史数据出口不修约——小数位仅监控页生效，此处只编辑单位。
          保存后按上方「单位=自定义」口径生效，页面将自动切换并重查。
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="unitDialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="unitSaving" :disabled="!unitRows.some((r) => r._dirty)" @click="saveUnitPrefs">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import * as echarts from 'echarts'
// keep-alive 契约：Options API 组件 name 必须等于路由 name（history_data），宿主 keep-alive 按组件名匹配缓存。

import { queryHistory, listStatParams, getSnapshot, getConfigUnit, putConfigUnit } from '@/api/asm'
import { formatLocalDateTime, formatLocalInputSeconds } from '@/utils/datetime'
import { fetchAllHistoryPages, downloadCsv, EXPORT_MAX_ROWS } from '@/utils/historyExport'

// 粒度 → applicableGranularityMask 位（与后端 AsmGranularityMask 同定义：bit0=minute/bit1=5min/bit2=hour）
const GRANULARITY_BIT = { MINUTE: 1, FIVE_MIN: 2, HOUR: 4 }

const GRANULARITY = [
  { value: 'MINUTE', label: '分钟' },
  { value: 'FIVE_MIN', label: '5分钟' },
  { value: 'HOUR', label: '小时' },
]
// 区间按钮文案简化为 后标/前标，原语义 (L,R]/[S,E) 经悬浮说明保留
const MODES = [
  { value: 'BACK', label: '后标' },
  { value: 'FRONT', label: '前标' },
]
const MODE_HINTS = {
  BACK: '后标 (L,R]：统计桶按闭合右端归属',
  FRONT: '前标 [S,E)：统计桶按闭合左端归属',
}
const UNITS = [
  { value: 'custom', label: '自定义' },
  { value: 'standard', label: '标准' },
]

// 参数勾选记忆（uid:attrId 数组）：跨会话恢复用户粘性选择
const CHECKED_STORAGE_KEY = 'asm-history-checked'

/**
 * 扁平行集 → series 分组。列基准=勾选集全集（checkedKeys 顺序）：勾选但窗口无数据的参数照常占列
 * （表头完整、单元格 '--'），不再随返回数据行裁剪列；meta 缺失回退 key 原文（uid/attrId 拆首个 ':'）。
 * unit 优先取行内 display_unit（随查询的 standard/custom 口径变化），行内无单位再回退
 * stat-params 元数据（display_unit→storageUnit）——无数据列只能取 meta 口径。
 */
function buildSeries(checkedKeys, rows, metaByKey) {
  const byKey = new Map()
  const out = []
  for (const key of checkedKeys) {
    const meta = metaByKey.get(key)
    const i = key.indexOf(':')
    const s = {
      key,
      deviceLabel: meta ? meta.deviceLabel : key.slice(0, i),
      paramName: meta ? meta.paramName : key.slice(i + 1),
      unit: '',
      points: [],
    }
    out.push(s)
    byKey.set(key, s)
  }
  for (const r of rows) {
    const s = byKey.get(r.logicDeviceUniqueId + ':' + r.attrId)
    // 行集键不在勾选集（查询在途时勾选被确认/清空改写的竞态）→ 以勾选集为列基准，无列可归则弃
    if (!s) continue
    s.points.push(r)
    if (!s.unit) s.unit = r.display_unit || r.unit || ''
  }
  for (const s of out) {
    if (!s.unit) {
      const meta = metaByKey.get(s.key)
      if (meta) s.unit = meta.unit
    }
    s.name = s.deviceLabel + '·' + s.paramName
  }
  return out
}

/** series → 时刻并集透视图（升序，旧→新）：行 cells={key→原始行（含 value/validCount/totalCount）}。 */
function pivotOf(rows, seriesArr) {
  const byTime = new Map()
  for (const s of seriesArr) {
    for (const p of s.points) {
      if (!byTime.has(p.dataTime)) byTime.set(p.dataTime, { dataTime: p.dataTime, cells: {} })
      byTime.get(p.dataTime).cells[s.key] = p
    }
  }
  return [...byTime.values()].sort((a, b) => (a.dataTime < b.dataTime ? -1 : 1))
}

export default {
  name: 'history_data',
  data() {
    const now = new Date()
    return {
      GRANULARITY, MODES, MODE_HINTS, UNITS,
      filter: {
        granularity: 'HOUR',
        mode: 'BACK',
        unit: 'custom',
        start: formatLocalInputSeconds(new Date(now.getTime() - 3600 * 1000)),
        end: formatLocalInputSeconds(now),
        pageNum: 1,
        pageSize: 200,
      },
      checked: [],
      devices: [],
      paramKeyword: '',
      paramDeviceUid: '',
      metaLoading: false,
      loading: false,
      exporting: false,
      rows: [],
      errorMsg: '',
      viewMode: 'list',
      chartLayout: 'split',
      chart: null,
      // 参数选择弹窗：dialogChecked=草稿（打开时从 checked 拷贝，确定才回填并重查，取消丢弃）
      paramDialogVisible: false,
      dialogChecked: [],
      // 单位及修约弹窗（HISTORY purpose）：snapshot 供设备/参数中文名与单位候选，config-unit 供已存偏好回显
      unitDialogVisible: false,
      unitLoading: false,
      unitSaving: false,
      unitError: '',
      unitSnapDevices: [],
      unitPrefs: [],
      unitDeviceUid: null,
      unitRows: [],
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
    applicableBit() {
      return GRANULARITY_BIT[this.filter.granularity] || 0
    },
    // 全参数索引 key(uid:attrId) → {deviceLabel, paramName, unit, mask}（回显/series 命名与勾选有效性共用）
    metaByKey() {
      const map = new Map()
      for (const d of this.devices) {
        for (const a of d.attrs) {
          map.set(d.uid + ':' + a.attrId, {
            key: d.uid + ':' + a.attrId,
            deviceLabel: d.label,
            paramName: a.paramDisplayName || a.attrId,
            unit: a.display_unit || a.storageUnit || '',
            mask: a.applicableGranularityMask || 0,
          })
        }
      }
      return map
    },
    // 弹窗搜索过滤：设备名/uid 命中→整组保留；否则只留参数名/attrId 命中的行
    filteredDevices() {
      // 两段筛选：设备下拉（精确选中，clearable 清空=全部）先行，参数关键字在剩余组内再滤参数名
      let pool = this.devices
      if (this.paramDeviceUid) {
        pool = pool.filter((d) => d.uid === this.paramDeviceUid)
      }
      const kw = this.paramKeyword.trim().toLowerCase()
      if (!kw) return pool
      return pool
        .map((d) => {
          if (String(d.label).toLowerCase().includes(kw) || d.uid.toLowerCase().includes(kw)) return d
          const attrs = d.attrs.filter((a) =>
            String(a.paramDisplayName || '').toLowerCase().includes(kw) || String(a.attrId).toLowerCase().includes(kw))
          return attrs.length ? { ...d, attrs } : null
        })
        .filter(Boolean)
    },
    // 参数触发框单行回显：单项直接显示中文名；多项=首项中文 + 「等 N 项」（完整清单见弹窗/表格列头）
    paramSummaryText() {
      if (!this.checked.length) return ''
      const m = this.metaByKey.get(this.checked[0])
      const label = m ? m.deviceLabel + '·' + m.paramName : this.checked[0]
      return this.checked.length > 1 ? `${label} 等 ${this.checked.length} 项` : label
    },
    // 提交集：勾选中当前粒度可物化的（不可物化的保留勾选态置灰、列头保留，仅查询时跳过）
    submittableKeys() {
      const bit = this.applicableBit
      return this.checked.filter((k) => {
        const m = this.metaByKey.get(k)
        return m && (m.mask & bit) !== 0
      })
    },
    series() {
      return buildSeries(this.checked, this.rows, this.metaByKey)
    },
    // 升序透视图（曲线用）；列表倒序=最新时刻在前
    pivotRows() {
      return pivotOf(this.rows, this.series)
    },
    tableRows() {
      return this.pivotRows.slice().reverse()
    },
    hasNext() {
      return this.rows.length >= this.filter.pageSize
    },
    // 下一页探测的 total 构造：有下一页时 +1 让 pager 放出下一页按钮；无则封顶在已取行数
    probedTotal() {
      const base = (this.filter.pageNum - 1) * this.filter.pageSize + this.rows.length
      return this.hasNext ? base + 1 : base
    },
    sameUnit() {
      return this.series.length > 1 && this.series.every((s) => (s.unit || '') === (this.series[0].unit || ''))
    },
    // 弹窗设备候选：仅含数值参数（attrGroup==2）的设备——单位只对数值行有意义（配置页同口径）
    unitDevices() {
      return this.unitSnapDevices
        .filter((d) => (d.attrs || []).some((a) => a.attrGroup === 2))
        .map((d) => ({ uid: d.logicDeviceUniqueId, label: d.displayName || d.logicDeviceUniqueId }))
        .sort((a, b) => String(a.label).localeCompare(String(b.label), 'zh-Hans-CN'))
    },
  },
  watch: {
    // 切到曲线：容器 v-show 生效后（nextTick）再 init/渲染，避免隐藏容器 0 尺寸初始化
    viewMode(v) {
      if (v === 'chart') this.$nextTick(() => { this.ensureChart(); this.renderChart() })
    },
    chartLayout() {
      this.renderChart()
    },
    unitDeviceUid() {
      this.buildUnitRows()
    },
  },
  mounted() {
    this.loadMeta()
    window.addEventListener('resize', this.resize)
  },
  // keep-alive 复活：容器尺寸可能已变（离开期间窗口缩放/侧栏折叠），补一次 resize
  activated() {
    if (this.chart) this.$nextTick(() => this.chart.resize())
  },
  beforeUnmount() {
    window.removeEventListener('resize', this.resize)
    if (this.chart) this.chart.dispose()
  },
  methods: {
    formatLocalDateTime,
    // —— 参数选择弹窗 ——
    attrUnitLabel(a) {
      return a.display_unit || a.storageUnit || ''
    },
    isAttrApplicable(a) {
      return ((a.applicableGranularityMask || 0) & this.applicableBit) !== 0
    },
    // 表列头是否当前粒度可物化（meta 缺失=曾被提交过，视为可物化，不加「不物化」悬浮）
    isSeriesMaterializable(s) {
      const m = this.metaByKey.get(s.key)
      return !m || (m.mask & this.applicableBit) !== 0
    },
    groupAllChecked(d) {
      const app = d.attrs.filter((a) => this.isAttrApplicable(a))
      return app.length > 0 && app.every((a) => this.dialogChecked.includes(d.uid + ':' + a.attrId))
    },
    groupPartialChecked(d) {
      const app = d.attrs.filter((a) => this.isAttrApplicable(a))
      const n = app.filter((a) => this.dialogChecked.includes(d.uid + ':' + a.attrId)).length
      return n > 0 && n < app.length
    },
    // 组级全选只作用于可物化参数；取消则清整组（含置灰勾选项——用户取消意图明确）。
    // 只改草稿 dialogChecked，确定才回填 checked 并重查。
    toggleGroup(d, val) {
      if (val) {
        const appKeys = d.attrs.filter((a) => this.isAttrApplicable(a)).map((a) => d.uid + ':' + a.attrId)
        this.dialogChecked = [...new Set([...this.dialogChecked, ...appKeys])]
      } else {
        const groupKeys = new Set(d.attrs.map((a) => d.uid + ':' + a.attrId))
        this.dialogChecked = this.dialogChecked.filter((k) => !groupKeys.has(k))
      }
    },
    openParamDialog() {
      this.dialogChecked = [...this.checked]
      this.paramKeyword = ''
      this.paramDeviceUid = ''
      this.paramDialogVisible = true
    },
    confirmParamDialog() {
      this.checked = [...this.dialogChecked]
      this.persistChecked()
      this.paramDialogVisible = false
      this.search()
    },
    // 触发框清除按钮：清空全部勾选并清结果（不发起零参数查询——那只会得到必败错误提示）
    clearAllChecked() {
      this.checked = []
      this.persistChecked()
      this.rows = []
      this.errorMsg = ''
    },
    persistChecked() {
      try {
        localStorage.setItem(CHECKED_STORAGE_KEY, JSON.stringify(this.checked))
      } catch (e) {
        // 隐私模式写失败 → 仅内存态（刷新丢失，不崩）
      }
    },
    // 勾选记忆恢复：过滤掉当前 meta 已不存在的键（设备/参数下线后不留死勾选）
    restoreChecked() {
      let stored = []
      try {
        const raw = localStorage.getItem(CHECKED_STORAGE_KEY)
        const parsed = raw ? JSON.parse(raw) : null
        if (Array.isArray(parsed)) stored = parsed.filter((k) => typeof k === 'string')
      } catch (e) {
        stored = [] // 脏数据 → 空勾选
      }
      const valid = new Set(this.metaByKey.keys())
      this.checked = stored.filter((k) => valid.has(k))
      if (this.checked.length !== stored.length) this.persistChecked()
    },
    // 重置：恢复默认窗（近 1 小时）/粒度/区间/单位并清参数勾选与结果（零参数不发起查询）
    resetQuery() {
      const now = new Date()
      this.filter.granularity = 'HOUR'
      this.filter.mode = 'BACK'
      this.filter.unit = 'custom'
      this.filter.start = formatLocalInputSeconds(new Date(now.getTime() - 3600 * 1000))
      this.filter.end = formatLocalInputSeconds(now)
      this.filter.pageNum = 1
      this.checked = []
      this.persistChecked()
      this.rows = []
      this.errorMsg = ''
    },
    // —— 查询 ——
    validate() {
      if (!this.filter.start || !this.filter.end) return '请选择开始和结束时间'
      const s = new Date(this.filter.start).getTime()
      const e = new Date(this.filter.end).getTime()
      if (Number.isNaN(s) || Number.isNaN(e)) return '时间格式无效'
      if (s >= e) return '开始时间须早于结束时间'
      return ''
    },
    queryBase() {
      return {
        granularity: this.filter.granularity,
        start: this.filter.start,
        end: this.filter.end,
        params: this.submittableKeys.join(','),
        mode: this.filter.mode,
        unit: this.filter.unit,
      }
    },
    async loadMeta() {
      this.metaLoading = true
      try {
        // stat-params（SDK 同源）：只列已建聚合配置的参数（有 stat 桶可查）；
        // device_label/display_unit 为并行追加契约字段，缺失回退 uniqueId/storageUnit
        const res = await listStatParams()
        const metas = (res && res.data) || []
        const map = new Map()
        for (const m of metas) {
          if (!map.has(m.logicDeviceUniqueId)) {
            map.set(m.logicDeviceUniqueId, {
              uid: m.logicDeviceUniqueId,
              label: m.device_label || m.logicDeviceUniqueId,
              attrs: [],
            })
          }
          map.get(m.logicDeviceUniqueId).attrs.push(m)
        }
        this.devices = [...map.values()]
        this.restoreChecked()
      } finally {
        this.metaLoading = false
      }
    },
    async load() {
      const err = this.validate()
      if (err) { this.errorMsg = err; return }
      if (!this.submittableKeys.length) { this.errorMsg = '请至少勾选一个当前粒度可物化的参数'; return }
      this.errorMsg = ''
      this.loading = true
      try {
        const res = await queryHistory({ ...this.queryBase(), pageNum: this.filter.pageNum, pageSize: this.filter.pageSize })
        this.rows = (res && res.data && res.data.rows) || []
        if (this.viewMode === 'chart') { this.ensureChart(); this.renderChart() }
      } catch (e) {
        this.rows = []
        this.errorMsg = (e && e.message) || '查询失败'
        if (this.viewMode === 'chart') this.renderChart()
      } finally {
        this.loading = false
      }
    },
    search() {
      this.filter.pageNum = 1
      this.load()
    },
    turnPage(p) {
      this.filter.pageNum = p
      this.load()
    },
    // —— 表格单元格 ——
    cellText(p) {
      return p.value == null ? '--' : p.value
    },
    partialValid(p) {
      return p.validCount != null && p.totalCount != null && p.validCount < p.totalCount
    },
    validHint(p) {
      return `有效 ${p.validCount}/共 ${p.totalCount}`
    },
    // —— 曲线 ——
    resize() {
      if (this.chart) this.chart.resize()
    },
    ensureChart() {
      if (!this.chart && this.$refs.chartEl) this.chart = echarts.init(this.$refs.chartEl)
    },
    renderChart() {
      if (this.viewMode !== 'chart' || !this.chart) return
      const times = this.pivotRows.map((r) => formatLocalDateTime(r.dataTime, true))
      // 空序列仍走 clear 清残图；有数据用 notMerge(true) 全量替换（ADM 同模式）
      if (!this.series.length) { this.chart.clear(); return }
      this.chart.setOption(
        this.chartLayout === 'merge' ? this.mergeChartOption(times) : this.splitChartOption(times), true)
      this.pageRealmReplay()
    },
    /**
     * 页面域重放（tooltip 挂载宿主缺陷的工程绕行）：本模块 dist 在宿主加载域执行，该域创建/驱动的
     * echarts 实例 Tooltip 视图永不挂载（真鼠标/派发均无提示、零报错；同一 option 同一元素改由
     * 页面域 setOption 即正常——多轮浏览器实验复现，证据与排除项见 bugs/bug-record-20260908-233000）。
     * 经注入同源 <script>（在页面域执行）对本图实例做一次 normalized option notMerge 重放，
     * 重放后 tooltip 恢复。幂等、无全局残留（script 用后即删，仅触达本组件图元素）。
     */
    pageRealmReplay() {
      const el = this.$refs.chartEl
      if (!el) return
      if (!el.id) el.id = 'asm-history-chart-el'
      const s = document.createElement('script')
      s.textContent = "(function(){var el=document.getElementById('" + el.id + "');"
        + "if(!el)return;var i=window.echarts.getInstanceByDom(el);"
        + "if(i&&!i.isDisposed()){i.setOption(i.getOption(),true);}})()"
      document.documentElement.appendChild(s)
      s.remove()
    },
    seriesData(s) {
      return this.pivotRows.map((r) => {
        const p = r.cells[s.key]
        return p && p.value != null ? p.value : null
      })
    },
    // 分图：每参数独立 grid，两列流式；子图标题=设备中文·参数中文 (display_unit)
    splitChartOption(times) {
      const n = this.series.length
      const rows = Math.ceil(n / 2)
      const gap = 4
      const topPad = 6
      const bottomPad = 6
      const colW = (100 - gap * 3) / 2
      const rowH = (100 - topPad - bottomPad) / rows
      const grids = []
      const xAxes = []
      const yAxes = []
      const titles = []
      const sers = []
      this.series.forEach((s, i) => {
        const c = i % 2
        const r = Math.floor(i / 2)
        const left = gap + c * (colW + gap)
        const top = topPad + r * rowH
        grids.push({ left: left + '%', width: colW + '%', top: top + '%', height: rowH - 3 + '%' })
        // 仅底行显示时刻轴标签、仅尾行右侧留白收尾，中间行去标签省纵向空间
        xAxes.push({ type: 'category', data: times, gridIndex: i, axisLabel: { show: r === rows - 1 } })
        yAxes.push({ type: 'value', scale: true, gridIndex: i })
        titles.push({
          text: s.name + (s.unit ? ' (' + s.unit + ')' : ''),
          left: left + '%',
          top: Math.max(0, top - 3.2) + '%',
          textStyle: { fontSize: 12 },
        })
        // 单点序列必须显示符号：showSymbol:false 下单点无线段可画=子图空白（1h 窗口小时粒度常态），多点保持净线
        sers.push({ name: s.name, type: 'line', showSymbol: s.points.length <= 1, symbolSize: 7, connectNulls: true, xAxisIndex: i, yAxisIndex: i, data: this.seriesData(s) })
      })
      return {
        // 自定义 formatter 实测会杀死 tooltip 视图挂载（本宿主 vite+echarts5.5.1 组合，带函数的
        // tooltip 与无函数的互斥实验定位），默认渲染已按时刻逐 series 出值；单位见子图标题括号
        tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
        grid: grids,
        xAxis: xAxes,
        yAxis: yAxes,
        title: titles,
        series: sers,
      }
    },
    // 合并：一图多 series，tooltip 逐 series 带 display_unit
    mergeChartOption(times) {
      return {
        // formatter 去除原因同分图（函数与 tooltip 挂载互斥）；单位在 series 名尾或由默认渲染给出
        tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
        legend: { type: 'scroll', bottom: 0 },
        grid: { left: 60, right: 24, top: 30, bottom: 44 },
        xAxis: { type: 'category', data: times },
        yAxis: { type: 'value', scale: true },
        series: this.series.map((s) => ({
          name: s.name,
          type: 'line',
          showSymbol: s.points.length <= 1,  // 单点序列显示符号（同分图：否则空白）
          symbolSize: 7,
          connectNulls: true,
          data: this.seriesData(s),
        })),
      }
    },
    // —— 导出（CSV：本集成 vue-modules 无 xlsx 且禁新增依赖；循环分页拉全量，上限防炸） ——
    async exportCsv() {
      const err = this.validate()
      if (err) { this.errorMsg = err; return }
      if (!this.submittableKeys.length) { this.errorMsg = '请至少勾选一个当前粒度可物化的参数'; return }
      this.exporting = true
      this.errorMsg = ''
      try {
        const base = this.queryBase()
        const { rows: all, truncated } = await fetchAllHistoryPages(
          ({ pageNum, pageSize }) => queryHistory({ ...base, pageNum, pageSize }))
        if (truncated && this.$message) this.$message.warning(`数据量超过 ${EXPORT_MAX_ROWS} 行上限，仅导出前 ${EXPORT_MAX_ROWS} 行`)
        // 列基准与表格一致=勾选集全集（不可物化/无数据列头保留、值空）
        const sList = buildSeries(this.checked, all, this.metaByKey)
        const piv = pivotOf(all, sList)
        const header = ['时刻', ...sList.map((s) => s.name + (s.unit ? ` (${s.unit})` : ''))]
        const dataRows = piv.map((r) => [
          formatLocalDateTime(r.dataTime),
          ...sList.map((s) => {
            const p = r.cells[s.key]
            return p && p.value != null ? p.value : ''
          }),
        ])
        const stamp = formatLocalInputSeconds(new Date()).replace(/\D/g, '')
        downloadCsv(`asm_history_${this.filter.granularity}_${stamp}.csv`, header, dataRows)
      } catch (e) {
        this.errorMsg = (e && e.message) || '导出失败'
      } finally {
        this.exporting = false
      }
    },
    // —— 单位及修约弹窗（HISTORY purpose，与配置页单位 tab 同后端同数据同端点） ——
    async openUnitDialog() {
      this.unitDialogVisible = true
      this.unitError = ''
      this.unitLoading = true
      try {
        // snapshot 与 config-unit 分属 monitor/config 权限域：一侧失败不拖 blank 另一侧（配置页 load 同口径）
        const [snap, prefs] = await Promise.allSettled([getSnapshot('custom'), getConfigUnit()])
        if (snap.status === 'fulfilled') this.unitSnapDevices = (snap.value && snap.value.data) || []
        else this.unitError = '设备清单加载失败（' + ((snap.reason && snap.reason.message) || '无权限或服务异常') + '）'
        if (prefs.status === 'fulfilled') this.unitPrefs = (prefs.value && prefs.value.data) || []
        else if (!this.unitError) this.unitError = '已存偏好加载失败，按原生单位回显（保存仍可用）'
        if (!this.unitDeviceUid && this.unitDevices.length) this.unitDeviceUid = this.unitDevices[0].uid
        this.buildUnitRows()
      } finally {
        this.unitLoading = false
      }
    },
    // 行编辑态重建：数值行（attrGroup==2）；HISTORY 回显=已存偏好（无行='' 即原生）
    buildUnitRows() {
      const dev = this.unitSnapDevices.find((d) => d.logicDeviceUniqueId === this.unitDeviceUid)
      if (!dev) { this.unitRows = []; return }
      const prefs = new Map(this.unitPrefs
        .filter((p) => p.logicDeviceUniqueId === dev.logicDeviceUniqueId && p.purpose === 'HISTORY')
        .map((p) => [p.attrId, p]))
      this.unitRows = (dev.attrs || [])
        .filter((a) => a.attrGroup === 2)
        .sort((a, b) => String(a.displayName || a.attrId).localeCompare(String(b.displayName || b.attrId), 'zh-Hans-CN'))
        .map((a) => {
          const p = prefs.get(a.attrId)
          return {
            attrId: a.attrId,
            displayName: a.displayName || a.attrId,
            unitOptions: a.unitOptions || null,
            unitSymbol: a.unit,
            historyUnit: p ? (p.unit || '') : '',
            _dirty: false,
          }
        })
    },
    markUnitDirty(r) {
      r._dirty = true
    },
    async saveUnitPrefs() {
      const uid = this.unitDeviceUid
      if (!uid) return
      this.unitSaving = true
      try {
        // 只写 HISTORY 单位——小数位历史出口不消费（AsmUnitContract.monitorDisplayPrecision 契约，配置页同语义不写无效字段）
        for (const r of this.unitRows.filter((x) => x._dirty)) {
          await putConfigUnit({ logicDeviceUniqueId: uid, attrId: r.attrId, purpose: 'HISTORY', unit: r.historyUnit || null })
        }
        this.unitDialogVisible = false
        this.$message && this.$message.success('历史查询单位已保存')
        const res = await getConfigUnit()
        this.unitPrefs = (res && res.data) || []
        this.buildUnitRows()
        // HISTORY 偏好仅在 unit=custom 口径生效：保存后切到自定义立即重查看到换算结果
        this.filter.unit = 'custom'
        this.search()
      } catch (e) {
        this.unitError = (e && e.message) || '保存失败'
      } finally {
        this.unitSaving = false
      }
    },
  },
}
</script>

<style scoped>
/* 整页列式布局（ruoyi 一屏范式）：搜索表单/操作行/分页固定高，结果主体 flex 占满剩余视口——
   84px = 宿主 navbar(50) + tags-view(34)；box-sizing 含 12px 内边距，页面级不滚动、表格内部滚 */
.asm-history { display: flex; flex-direction: column; height: calc(100vh - 84px); min-height: 480px; box-sizing: border-box; padding: 12px; }
.asm-filter { flex-shrink: 0; }

/* 参数触发框：清除/下拉箭头 suffix 图标（readonly 下原生 clearable 不渲染，手工置；样式对齐 el-input__clear） */
.asm-param-input { width: 320px; }
.asm-param-input :deep(.el-input__inner) { cursor: pointer; }
.asm-param-clear { color: #a8abb2; cursor: pointer; }
.asm-param-clear:hover { color: var(--el-color-info, #606266); }
.asm-param-arrow { color: #a8abb2; }

/* 操作行：右侧视图切换（列对齐用 flex 尾推，不依赖 span 求和） */
.asm-result-head { flex-shrink: 0; }
.asm-view-col { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.asm-chart-layout { margin-left: 0; }
.asm-merge-hint { font-size: 12px; color: #909399; }

/* 参数选择弹窗：搜索框 + 设备分组多列（组头中文+组级全选），分组区限高 60vh 内滚（弹窗自身不滚） */
.asm-param-filter-row { display: flex; gap: 8px; margin-bottom: 8px; }
.asm-param-device { width: 260px; }
.asm-param-search { width: 240px; }
/* 两列 grid（行序填充）：设备组为原子项不拆分、同组设备（空调1/2 等相邻命名）落在同一行相邻格、
   行高自动对齐无错落。multicol 与 flex-column-wrap 两案否决——受限高度下均横向溢出成 N 列 */
.asm-param-groups { display: grid; grid-template-columns: repeat(2, minmax(240px, 1fr)); gap: 8px 28px; align-items: start; align-content: start; max-height: 60vh; overflow-y: auto; }
.asm-param-group { min-width: 0; }
.asm-param-head { font-weight: 600; margin-bottom: 4px; background: #f5f7fa; border-radius: 4px; padding: 4px 10px; }
.asm-param-attrs { padding-left: 22px; }
.asm-param-attrs { display: flex; flex-direction: column; gap: 2px; align-items: flex-start; }
.asm-param-attrs :deep(.el-checkbox__label) { font-size: 13px; font-weight: 400; }
.asm-empty-inline { color: #909399; padding: 8px; width: 100%; }

/* 结果区 */
.asm-result-body { flex: 1; min-height: 0; display: flex; }
.asm-table-wrap { flex: 1; min-width: 0; position: relative; }
.asm-chart-wrap { flex: 1; min-width: 0; position: relative; }
.asm-chart { width: 100%; height: 100%; }
.asm-chart-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: #fff; }
.asm-pager { display: flex; justify-content: flex-end; align-items: center; gap: 10px; margin-top: 8px; flex-shrink: 0; }
.asm-pager-count { font-size: 12px; color: #606266; }

/* 表列头两行：参数中文 + (display_unit) */
.asm-col-head { cursor: default; }
.asm-col-name { font-weight: 600; }
.asm-col-unit { font-size: 12px; color: #909399; font-weight: 400; }
/* 有效性标记：值后 · 悬浮「有效 N/共 M」（validCount<totalCount 才显） */
.asm-valid-dot { color: #e6a23c; font-weight: 700; cursor: help; margin-left: 2px; }
.asm-muted { color: #c0c4cc; }

/* 单位弹窗行表（配置页单位 tab 同形态） */
.asm-unit-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.asm-unit-table th, .asm-unit-table td { border-bottom: 1px solid #ebeef5; padding: 6px 8px; text-align: left; }
.asm-unit-table th { background: #fafafa; color: #606266; }
.asm-hint { margin-top: 8px; font-size: 12px; color: #909399; line-height: 1.6; }
</style>
