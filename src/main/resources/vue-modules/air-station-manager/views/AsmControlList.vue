<template>
  <!--
    控制记录（路由 name=control_list）：REMOTE 控制执行 + 审计历史列表。
    列表读 GET /asm-monitor/control-record/list（时间窗/uid/origin/result 过滤+分页）；
    执行后自动刷新列表；PENDING 行为异步终态——「刷新」按钮手动回查（无自动轮询，避免后台空转）。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">站房设备控制</span></div>

    <div class="asm-filter">
      <div class="asm-row">
        <span class="asm-label">设备</span>
        <select v-model="form.uid">
          <option value="">请选择</option>
          <option v-for="d in devices" :key="d.logicDeviceUniqueId" :value="d.logicDeviceUniqueId">
            {{ d.logicDeviceUniqueId }}
          </option>
        </select>
        <span class="asm-label">参数</span>
        <select v-model="form.attrId">
          <option value="">请选择</option>
          <option v-for="a in currentAttrs" :key="a.attrId" :value="a.attrId">{{ a.attrId }}</option>
        </select>
        <span class="asm-label">值</span>
        <input v-model="form.value" placeholder="写值 / 选项 key" style="width: 140px" />
        <button class="asm-btn primary" :disabled="!form.uid || !form.attrId || !form.value || executing" @click="execute">
          {{ executing ? '执行中…' : '执行控制' }}
        </button>
      </div>
      <div class="asm-row">
        <span class="asm-label">时间</span>
        <input type="datetime-local" step="1" v-model="filter.start" />
        <span>~</span>
        <input type="datetime-local" step="1" v-model="filter.end" />
        <span class="asm-label">来源</span>
        <select v-model="filter.origin">
          <option value="">全部</option>
          <option value="REMOTE">REMOTE</option>
          <option value="LOCAL">LOCAL</option>
        </select>
        <span class="asm-label">结果</span>
        <select v-model="filter.result">
          <option value="">全部</option>
          <option v-for="r in RESULTS" :key="r" :value="r">{{ r }}</option>
        </select>
        <button class="asm-btn primary" :disabled="loading" @click="search">查询</button>
        <button class="asm-btn" :disabled="loading" @click="loadList">刷新（PENDING 终态回查）</button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading || executing" size="small" border>
      <el-table-column prop="id" label="记录ID" width="80" />
      <el-table-column label="时刻" width="170">
        <template #default="{ row }">{{ formatLocalDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="origin" label="来源" width="90" />
      <el-table-column prop="caller" label="调用方" min-width="120" show-overflow-tooltip />
      <el-table-column prop="logicDeviceUniqueId" label="设备" min-width="170" show-overflow-tooltip />
      <el-table-column prop="attrId" label="参数" min-width="110" />
      <el-table-column prop="action" label="动作" width="90" />
      <el-table-column prop="beforeValue" label="执行前" min-width="120" show-overflow-tooltip />
      <el-table-column prop="requestedValue" label="请求值" min-width="100" />
      <el-table-column prop="afterValue" label="执行后" min-width="120" show-overflow-tooltip />
      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <el-tag :type="resultTag(row.result)" size="small">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时ms" width="90" />
      <el-table-column prop="error" label="错误" min-width="160" show-overflow-tooltip />
    </el-table>
    <div class="asm-pager">
      <button class="asm-btn" :disabled="filter.pageNum <= 1 || loading" @click="turn(-1)">上一页</button>
      <span>第 {{ filter.pageNum }} 页 / 共 {{ total }} 条</span>
      <button class="asm-btn" :disabled="!hasNext || loading" @click="turn(1)">下一页</button>
    </div>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（control_list），宿主 keep-alive 按组件名匹配缓存。

import { executeControl, getSnapshot, listControlRecords } from '@/api/asm'
import { formatLocalDateTime, formatLocalInputSeconds } from '@/utils/datetime'

const RESULTS = ['PENDING', 'SUCCESS', 'FAILED', 'TIMEOUT']

export default {
  name: 'control_list',
  data() {
    const now = new Date()
    return {
      RESULTS,
      devices: [],
      form: { uid: '', attrId: '', value: '' },
      filter: {
        start: formatLocalInputSeconds(new Date(now.getTime() - 24 * 3600 * 1000)),
        end: formatLocalInputSeconds(now),
        origin: '',
        result: '',
        pageNum: 1,
        pageSize: 50,
      },
      rows: [],
      total: 0,
      loading: false,
      executing: false,
    }
  },
  computed: {
    currentAttrs() {
      const d = this.devices.find((x) => x.logicDeviceUniqueId === this.form.uid)
      return d ? d.attrs : []
    },
    hasNext() {
      return this.filter.pageNum * this.filter.pageSize < this.total
    },
  },
  watch: {
    'form.uid'() {
      this.form.attrId = ''
    },
  },
  mounted() {
    getSnapshot().then((res) => { this.devices = (res && res.data) || [] }).catch(() => {})
    this.loadList()
  },
  methods: {
    formatLocalDateTime,
    resultTag(r) {
      return { SUCCESS: 'success', FAILED: 'danger', TIMEOUT: 'warning', PENDING: 'info' }[r] || 'info'
    },
    turn(delta) {
      this.filter.pageNum += delta
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
    async execute() {
      this.executing = true
      try {
        const res = await executeControl({ uid: this.form.uid, attrId: this.form.attrId, value: this.form.value })
        const rec = (res && res.data) || {}
        if (rec.result !== 'SUCCESS') {
          this.$message && this.$message.warning('控制未成功终态：' + rec.result + (rec.error ? ' / ' + rec.error : ''))
        }
        // 执行后回拉列表（窗口内最新行置顶；PENDING 终态由「刷新」按钮回查）
        this.filter.pageNum = 1
        await this.loadList()
      } finally {
        this.executing = false
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
.asm-label { color: #606266; font-size: 13px; }
.asm-btn { padding: 4px 14px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; cursor: pointer; }
.asm-btn.primary { background: #409eff; color: #fff; }
.asm-btn:disabled { opacity: .6; }
.asm-pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; color: #606266; }
</style>
