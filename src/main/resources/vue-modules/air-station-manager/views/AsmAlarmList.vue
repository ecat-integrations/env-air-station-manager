<template>
  <!--
    报警记录（路由 name=alarm_list）：时间窗 + 设备过滤分页查询 GET /asm-monitor/alarm-record/list。
    窗口按触发/恢复时刻（end_time）落窗；start/end 为 datetime-local 壁钟串原样提交。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">报警记录</span></div>

    <div class="asm-filter">
      <div class="asm-row">
        <span class="asm-label">时间</span>
        <input type="datetime-local" step="1" v-model="filter.start" />
        <span>~</span>
        <input type="datetime-local" step="1" v-model="filter.end" />
        <span class="asm-label" style="margin-left:12px">设备</span>
        <select v-model="filter.uid">
          <option value="">全部</option>
          <option v-for="d in devices" :key="d.logicDeviceUniqueId" :value="d.logicDeviceUniqueId">
            {{ d.logicDeviceUniqueId }}
          </option>
        </select>
        <button class="asm-btn primary" :disabled="loading" @click="search">查询</button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" size="small" border>
      <el-table-column label="触发时刻" width="170">
        <template #default="{ row }">{{ formatLocalDateTime(row.endTime) }}</template>
      </el-table-column>
      <el-table-column label="起始时刻" width="170">
        <template #default="{ row }">{{ formatLocalDateTime(row.startTime) }}</template>
      </el-table-column>
      <el-table-column prop="ruleName" label="规则名" min-width="130" />
      <el-table-column prop="alarmType" label="报警类型" min-width="150" />
      <el-table-column prop="logicDeviceUniqueId" label="设备" min-width="170" show-overflow-tooltip />
      <el-table-column prop="attrId" label="参数" min-width="110" />
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="severityTag(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
    </el-table>

    <div class="asm-pager">
      <button class="asm-btn" :disabled="filter.pageNum <= 1 || loading" @click="turn(-1)">上一页</button>
      <span>共 {{ total }} 条 / 第 {{ filter.pageNum }} 页</span>
      <button class="asm-btn" :disabled="filter.pageNum * filter.pageSize >= total || loading" @click="turn(1)">下一页</button>
    </div>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（alarm_list），宿主 keep-alive 按组件名匹配缓存。

import { listAlarmRecords, getSnapshot } from '@/api/asm'
import { formatLocalDateTime, formatLocalInputSeconds } from '@/utils/datetime'

export default {
  name: 'alarm_list',
  data() {
    const now = new Date()
    return {
      filter: {
        start: formatLocalInputSeconds(new Date(now.getTime() - 7 * 24 * 3600 * 1000)),
        end: formatLocalInputSeconds(now),
        uid: '',
        pageNum: 1,
        pageSize: 50,
      },
      devices: [],
      rows: [],
      total: 0,
      loading: false,
    }
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
    turn(delta) {
      this.filter.pageNum += delta
      this.search()
    },
    async search() {
      this.loading = true
      try {
        const res = await listAlarmRecords({
          start: this.filter.start, end: this.filter.end,
          uid: this.filter.uid, pageNum: this.filter.pageNum, pageSize: this.filter.pageSize,
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
.asm-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.asm-label { color: #606266; font-size: 13px; }
.asm-btn { padding: 4px 14px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; cursor: pointer; }
.asm-btn.primary { background: #409eff; color: #fff; }
.asm-pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; color: #606266; }
</style>
