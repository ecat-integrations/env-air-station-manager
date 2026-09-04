<template>
  <!--
    控制记录（路由 name=control_list）：控制审计历史只读查询页。
    列表读 GET /asm-monitor/control-record/list（时间窗/uid/origin/result 过滤+分页）；
    执行下发职责归设备控制页（device_control，card 墙 + SSE 终态徽章）；PENDING 行为异步终态——
    「查询」重查对齐（无自动轮询，避免后台空转）。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">控制记录</span></div>

    <!-- @submit.prevent：包进 el-form 后阻原生隐式提交（Enter 整页刷新），旧裸 div 无此风险 -->
    <el-form class="asm-filter" :inline="true" @submit.prevent>
      <div class="asm-row">
        <el-form-item label="时间">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :default-time="defaultTimeRange"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
        </el-form-item>
        <el-form-item label="设备">
          <el-select v-model="filter.uid" filterable clearable placeholder="全部设备" style="width: 200px">
            <el-option v-for="o in deviceOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="filter.origin" clearable placeholder="全部来源" style="width: 110px">
            <el-option v-for="o in ORIGIN_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="filter.result" clearable placeholder="全部结果" style="width: 110px">
            <el-option v-for="o in RESULT_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="loading" @click="search">查询</el-button>
        </el-form-item>
      </div>
    </el-form>

    <el-table :data="rows" v-loading="loading" size="small" border>
      <el-table-column label="时刻" width="170">
        <template #default="{ row }">{{ formatLocalDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">{{ originLabel(row.origin) }}</template>
      </el-table-column>
      <el-table-column prop="caller" label="调用方" min-width="120" show-overflow-tooltip />
      <el-table-column label="设备" min-width="170">
        <template #default="{ row }">
          <!-- 中文名为主显；悬浮显 raw uniqueId（uid 是 API 过滤键，记录行须可溯源） -->
          <el-tooltip
            :disabled="uidLabel(row.logicDeviceUniqueId) === row.logicDeviceUniqueId"
            :content="row.logicDeviceUniqueId"
            placement="top"
          >
            <span>{{ uidLabel(row.logicDeviceUniqueId) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="attrId" label="参数" min-width="110" />
      <el-table-column prop="action" label="动作" width="90" />
      <el-table-column prop="beforeValue" label="执行前" min-width="120" show-overflow-tooltip />
      <el-table-column prop="requestedValue" label="请求值" min-width="100" />
      <el-table-column prop="afterValue" label="执行后" min-width="120" show-overflow-tooltip />
      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <!-- 失败/超时行错误详情不占列：悬浮结果 tag 查看（无错误时 tooltip 关闭） -->
          <el-tooltip :disabled="!row.error" :content="row.error || ''" placement="top">
            <el-tag :type="resultTag(row.result)" size="small">{{ resultLabel(row.result) }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时ms" width="90" />
    </el-table>
    <el-pagination
      style="margin-top: 10px"
      small
      layout="total, prev, pager, next"
      :total="total"
      :page-size="filter.pageSize"
      :current-page="filter.pageNum"
      :disabled="loading"
      @current-change="onPageChange"
    />
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（control_list），宿主 keep-alive 按组件名匹配缓存。

import { listControlRecords } from '@/api/asm'
import { formatLocalDateTime, formatLocalInputSeconds } from '@/utils/datetime'
import { labelOf, typeOf } from '../stationParamMeta'

// uniqueId→中文槽名转换表（本页专用，stationParamMeta 只有 枚举名→中文，缺 uid 维度）。
// uid 公式与后端 StationParamMeta#getUniqueId 一致：logicdevice_station. + 槽键小写（槽键=typeOf(枚举名)）；
// 中文名=labelOf(枚举名)（多实例槽各自中文名：空调1/空调2、SO2标气…）。两个真相源都在
// stationParamMeta.js，此处仅枚举 37 槽枚举名做展开（键集与 META 逐一对照维护）；
// 未知 uid 如实显原串（严格模式不猜）。选项序=枚举序（同 sidebar 分组序）。
const STATION_UID_PREFIX = 'logicdevice_station.'
const SLOT_ENUM_NAMES = [
  'TH', 'CLEANLINESS', 'INDOOR_POLLUTANT',
  'POWER_METER', 'VOLTAGE_REGULATOR', 'UPS',
  'AIR_CONDITIONER_AC1', 'AIR_CONDITIONER_AC2', 'EXHAUST_FAN', 'LIGHTING',
  'SAMPLING_TUBE', 'ZERO_GAS_RELAY', 'STANDARD_GAS_SO2', 'STANDARD_GAS_CO', 'STANDARD_GAS_NOX',
  'CALIBRATOR',
  'FILTER_CHANGER_SO2', 'FILTER_CHANGER_CO', 'FILTER_CHANGER_O3', 'FILTER_CHANGER_NOX',
  'VALVE_GROUP_SO2', 'VALVE_GROUP_CO', 'VALVE_GROUP_NO', 'VALVE_GROUP_O3',
  'PM_ZERO_CHECK_PM10', 'PM_ZERO_CHECK_PM25',
  'CUTTER_CHANGER_PM10', 'CUTTER_CHANGER_PM25',
  'PAPER_TAPE_PM10', 'PAPER_TAPE_PM25',
  'SECURITY_ALARM', 'ELECTRONIC_FENCE', 'ACCESS_CONTROL',
  'CAMERA_1', 'CAMERA_2', 'CAMERA_3', 'CAMERA_4',
]
const DEVICE_OPTIONS = SLOT_ENUM_NAMES.map((name) => ({
  value: STATION_UID_PREFIX + typeOf(name).toLowerCase(),
  label: labelOf(name),
}))
const UID_LABELS = DEVICE_OPTIONS.reduce((m, o) => { m[o.value] = o.label; return m }, {})

// 来源/结果枚举中文映射：value 原样透传后端（过滤参数+行数据），仅展示层翻译
const ORIGIN_OPTIONS = [
  { value: 'REMOTE', label: '远程' },
  { value: 'LOCAL', label: '本地' },
]
const RESULT_OPTIONS = [
  { value: 'PENDING', label: '执行中' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'TIMEOUT', label: '超时' },
]
const ORIGIN_LABELS = ORIGIN_OPTIONS.reduce((m, o) => { m[o.value] = o.label; return m }, {})
const RESULT_LABELS = RESULT_OPTIONS.reduce((m, o) => { m[o.value] = o.label; return m }, {})

export default {
  name: 'control_list',
  data() {
    const now = new Date()
    return {
      ORIGIN_OPTIONS,
      RESULT_OPTIONS,
      deviceOptions: DEVICE_OPTIONS,
      filter: {
        start: formatLocalInputSeconds(new Date(now.getTime() - 24 * 3600 * 1000)),
        // 默认终点=挂载当天所属的明天 00:00（Date 构造器自理月/年进位）。不能冻结在挂载时刻：end 只在
        // data() 初始化一次，「查询」复用它，挂载后新建的控制记录 created_at 会永落窗外、刷新永不可见
        // （bug-record-20260901-084100）。必须到明天 00:00 而非当天 23:59:59：后端对 end 是亚秒严格比较，
        // 秒截断不够覆盖当天每一秒。用户显式手选历史窗天然不受影响——此值仅初始一次，无任何自动逻辑改写。
        // 已知边界（接受）：keep-alive 缓存页跨天使用时 end 停在前一天的 00:00，需刷新页面或手改时间窗，
        // 不为此加自动前进逻辑（保持本页纯查询、零后台行为的从简设计）。
        end: formatLocalInputSeconds(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)),
        uid: '',
        origin: '',
        result: '',
        pageNum: 1,
        pageSize: 50,
      },
      rows: [],
      total: 0,
      loading: false,
      // datetimerange 面板只挑日期（未选时刻）时的默认时刻；默认时间窗本身由上面 start/end 初始化决定
      defaultTimeRange: [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)],
    }
  },
  computed: {
    // el-date-picker datetimerange 绑定 [start, end] 数组；getter/setter 与 filter.start/end 两字段互拆，
    // 提交口径不变（壁钟串 'YYYY-MM-DDTHH:mm:ss' 原样透传，value-format 同串保证逐字节不变）。清空置空串与旧 input 清空一致。
    timeRange: {
      get() {
        return [this.filter.start, this.filter.end]
      },
      set(v) {
        this.filter.start = (v && v[0]) || ''
        this.filter.end = (v && v[1]) || ''
      },
    },
  },
  mounted() {
    this.loadList()
  },
  methods: {
    formatLocalDateTime,
    resultTag(r) {
      return { SUCCESS: 'success', FAILED: 'danger', TIMEOUT: 'warning', PENDING: 'info' }[r] || 'info'
    },
    // 展示层枚举翻译：未知值如实显原串（与 labelOf 同纪律）
    uidLabel(uid) {
      return UID_LABELS[uid] || uid
    },
    originLabel(v) {
      return ORIGIN_LABELS[v] || v
    },
    resultLabel(v) {
      return RESULT_LABELS[v] || v
    },
    onPageChange(page) {
      this.filter.pageNum = page
      this.loadList()
    },
    search() {
      this.filter.pageNum = 1
      this.loadList()
    },
    async loadList() {
      this.loading = true
      try {
        const res = await listControlRecords({
          start: this.filter.start,
          end: this.filter.end,
          uid: this.filter.uid,
          origin: this.filter.origin,
          result: this.filter.result,
          pageNum: this.filter.pageNum,
          pageSize: this.filter.pageSize,
        })
        const data = (res && res.data) || {}
        this.rows = data.rows || []
        this.total = data.total || 0
      } finally {
        this.loading = false
      }
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
</style>
