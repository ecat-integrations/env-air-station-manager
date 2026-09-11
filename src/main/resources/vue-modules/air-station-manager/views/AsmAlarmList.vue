<template>
  <!--
    报警记录（路由 name=alarm_list）：时间窗 + 设备/状态过滤分页查询 GET /asm-monitor/alarm-record/list。
    契约字段（后端保证不改名）：device_label/attr_label（槽/参数中文名，缺失回退 uniqueId/attrId）、
    trigger_time/recover_time/duration_ms（活跃行 recover_time/duration_ms 为 null）。
    持续时长列活跃行「持续中 …」按渲染时刻现算（无定时器；查询/刷新触发重渲染自然更新）；
    越限首事件时刻（原「起始时刻」列）退居触发时刻单元格 title 悬浮。
    窗口 = episode 区间重叠（后端 overlapWindow 谓词，与 SDK queryAlarmEntries 同口径）：
    持续中 ACTIVE 行默认视图天然可见；默认时间窗 = 昨天00:00 → 明天00:00。
    start/end 为壁钟串 'YYYY-MM-DDTHH:mm:ss' 原样提交
    （el-date-picker datetimerange 以 value-format 同串、提交前拆回两字段，线上格式逐字节不变）。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">报警记录</span></div>

    <!-- @submit.prevent：包进 el-form 后阻原生隐式提交（Enter 整页刷新），旧裸 div 无此风险 -->
    <el-form class="asm-filter" :inline="true" @submit.prevent>
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
        <!-- 设备下拉 label=槽中文名（快照 displayName）；value 保持 uniqueId 提交口径不变 -->
        <el-select v-model="filter.uid" filterable style="width: 220px">
          <el-option label="全部" value="" />
          <el-option
            v-for="d in devices"
            :key="d.logicDeviceUniqueId"
            :value="d.logicDeviceUniqueId"
            :label="d.displayName || d.logicDeviceUniqueId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filter.status" style="width: 140px" @change="onStatusPick">
          <el-option label="全部" value="" />
          <el-option label="活跃" value="ACTIVE" />
          <el-option label="已恢复" value="INACTIVE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :disabled="loading" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <!-- 列序（全中文）：状态 | 规则名 | 设备 | 参数 | 级别 | 触发时刻 | 恢复时刻 | 持续时长 | 报警详情。
         报警类型（alarmType 标识）列 UI 层删除——运维语义收敛到规则页「报警标识」。 -->
    <el-table :data="rows" v-loading="loading" size="small" border>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'danger' : 'success'" size="small">
            {{ row.status === 'ACTIVE' ? '活跃' : '已恢复' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ruleName" label="规则名" min-width="130" />
      <el-table-column label="设备" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.device_label || row.logicDeviceUniqueId }}</template>
      </el-table-column>
      <el-table-column label="参数" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.attr_label || row.attrId }}</template>
      </el-table-column>
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="severityTag(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="触发时刻" width="170">
        <template #default="{ row }">
          <!-- title 悬浮 = 越限首事件时刻（原「起始时刻」列撤出主列转此） -->
          <span :title="'越限首事件：' + formatLocalDateTime(row.startTime)">{{ formatLocalDateTime(row.trigger_time) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="恢复时刻" width="170">
        <template #default="{ row }">{{ row.status === 'ACTIVE' ? '' : formatLocalDateTime(row.recover_time) }}</template>
      </el-table-column>
      <el-table-column label="持续时长" width="130">
        <template #default="{ row }">{{ durationText(row) }}</template>
      </el-table-column>
      <el-table-column label="报警详情" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ descriptionText(row) }}</template>
      </el-table-column>
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
// keep-alive 契约：Options API 组件 name 必须等于路由 name（alarm_list），宿主 keep-alive 按组件名匹配缓存。

import { listAlarmRecords, getSnapshot } from '@/api/asm'
import { formatLocalDateTime, formatLocalInputSeconds } from '@/utils/datetime'

export default {
  name: 'alarm_list',
  data() {
    // 默认时间窗 = 昨天00:00 → 明天00:00（本地壁钟；Date 日期运算自动跨月/跨年进位）——
    // 覆盖昨夜至今触发的报警，且区间重叠口径下持续中 episode 默认可见
    const now = new Date()
    const dayStart = (offset) => new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset, 0, 0, 0)
    return {
      filter: {
        start: formatLocalInputSeconds(dayStart(-1)),
        end: formatLocalInputSeconds(dayStart(1)),
        uid: '',
        status: '',
        pageNum: 1,
        pageSize: 50,
      },
      devices: [],
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
    getSnapshot().then((res) => { this.devices = (res && res.data) || [] }).catch(() => {})
    this.search()
  },
  methods: {
    formatLocalDateTime,
    severityLabel(s) {
      return { 0: '普通', 1: '重要', 2: '紧急' }[s] || s
    },
    severityTag(s) {
      return { 0: 'info', 1: 'warning', 2: 'danger' }[s] || 'info'
    },
    // 毫秒 → 中文时长串：<1分 → X秒；<1时 → X分X秒；≥1时 → X小时X分（已恢复/持续中共用）
    formatDurationMs(ms) {
      if (ms == null || ms < 0 || Number.isNaN(ms)) return '-'
      const totalSec = Math.floor(ms / 1000)
      const h = Math.floor(totalSec / 3600)
      const m = Math.floor((totalSec % 3600) / 60)
      const s = totalSec % 60
      if (h > 0) return `${h}小时${m}分`
      if (m > 0) return `${m}分${s}秒`
      return `${s}秒`
    },
    // 持续时长列：活跃行 = trigger_time 距当前现算（渲染时算，无定时器；每次查询/刷新重渲染自然更新）；
    // 已恢复行 = duration_ms 格式化。缺失/不可解析如实显 '-'（不硬造）
    durationText(row) {
      if (row.status === 'ACTIVE') {
        const t = row.trigger_time ? new Date(row.trigger_time).getTime() : NaN
        if (Number.isNaN(t)) return '-'
        return `持续中 ${this.formatDurationMs(Date.now() - t)}`
      }
      return this.formatDurationMs(row.duration_ms)
    },
    // 报警详情：description 里的英文标识段（设备 uniqueId/attrId）用中文名替换重组；替换不了原样输出
    descriptionText(row) {
      let text = row.description || ''
      if (row.device_label && row.logicDeviceUniqueId) {
        text = text.split(row.logicDeviceUniqueId).join(row.device_label)
      }
      if (row.attr_label && row.attrId) {
        text = text.split(row.attrId).join(row.attr_label)
      }
      return text
    },
    // 状态筛选切换回第 1 页再查（避免停留在越界页号看到伪空态）
    onStatusPick() {
      this.filter.pageNum = 1
      this.search()
    },
    onPageChange(page) {
      this.filter.pageNum = page
      this.search()
    },
    async search() {
      this.loading = true
      try {
        this.filter.pageNum = Math.max(1, this.filter.pageNum)
        const res = await listAlarmRecords({
          start: this.filter.start, end: this.filter.end,
          uid: this.filter.uid, status: this.filter.status || undefined,
          pageNum: this.filter.pageNum, pageSize: this.filter.pageSize,
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
</style>
