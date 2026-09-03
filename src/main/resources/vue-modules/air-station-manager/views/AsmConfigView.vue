<template>
  <!--
    聚合单位配置（路由 name=config）：el-tabs 两签（统计物化运维 / 单位偏好）。
    统计物化 tab：config_stat（GET/PUT /asm-monitor/config-stat）逐行编辑 enabled/粒度掩码/物化范围；
      写语义=「配置不回溯历史」——下个物化 tick 按新配置走，不触发重算（保存响应 msg 注明）。
      设备/参数列消费后端契约字段 device_label/attr_label（后端并行追加），缺失回退 uniqueId/attrId 原样显示。
    单位偏好 tab：config_unit 的 MONITOR/HISTORY 行编辑，与监控页「⚙ 单位设置」抽屉同后端同数据
      同保存端点（snapshot 供参数中文名/单位候选/当前生效单位与精度，config-unit 供已存偏好回显）
      ——一处保存两处生效；写后后端失效单位缓存即时生效。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">聚合单位配置</span>
      <el-button size="small" :disabled="loading" @click="load">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="统计物化（运维）" name="stat">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="保存后下个物化周期生效，不回溯历史"
          style="margin-bottom: 10px"
        />

        <div class="asm-filter-bar">
          <el-select v-model="statFilterUid" filterable clearable placeholder="全部设备" size="small" style="width: 220px">
            <el-option v-for="d in statDevices" :key="d.uid" :value="d.uid" :label="d.label" />
          </el-select>
          <el-input v-model="statFilterKeyword" clearable placeholder="参数关键字" size="small" style="width: 180px" />
        </div>

        <el-table :data="filteredStatRows" v-loading="loading" size="small" border>
          <el-table-column label="设备" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.device_label || row.logicDeviceUniqueId }}</template>
          </el-table-column>
          <el-table-column label="参数" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.attr_label || row.attrId }}</template>
          </el-table-column>
          <el-table-column label="物化开关" width="100">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" :disabled="row._saving" @change="markDirty(row)" />
            </template>
          </el-table-column>
          <el-table-column label="粒度掩码（分|5分|时）" width="200">
            <template #default="{ row }">
              <el-select v-model="row.granularityMask" :disabled="row._saving" size="small" @change="markDirty(row)">
                <el-option v-for="m in MASKS" :key="m.value" :label="m.label" :value="m.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="物化范围" width="130">
            <template #default="{ row }">
              <el-select v-model="row.materializationMode" :disabled="row._saving" size="small" @change="markDirty(row)">
                <el-option v-for="m in MODES" :key="m" :label="m" :value="m" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button size="small" type="primary" :disabled="!row._dirty || row._saving" :loading="row._saving" @click="saveStat(row)">
                保存
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="单位偏好" name="unit">
        <div class="asm-filter-bar">
          <el-select v-model="unitDeviceUid" filterable clearable placeholder="选择设备" size="small" style="width: 260px">
            <el-option v-for="d in unitDevices" :key="d.uid" :value="d.uid" :label="d.label" />
          </el-select>
        </div>

        <div v-loading="loading">
          <table v-if="unitDevice && unitRows.length" class="asm-table">
            <thead>
              <tr><th>参数</th><th>监控显示单位</th><th>小数位</th><th>历史查询单位</th></tr>
            </thead>
            <tbody>
              <tr v-for="r in unitRows" :key="r.attrId">
                <td>{{ r.displayName }}</td>
                <td>
                  <el-select v-if="r.unitOptions" v-model="r.monitorUnit" size="small" style="width: 140px"
                             @change="markUnitDirty(r)">
                    <el-option label="原生（不换算）" value="" />
                    <el-option-group v-for="g in r.unitOptions" :key="g.classLabel" :label="g.classLabel">
                      <el-option v-for="u in g.units" :key="u.key" :value="u.key" :label="u.symbol" />
                    </el-option-group>
                  </el-select>
                  <span v-else class="asm-muted">{{ r.unitSymbol || '-' }}</span>
                </td>
                <td>
                  <el-input-number v-model="r.monitorPrecision" :min="0" :max="6" size="small"
                                   :placeholder="String(r.resolvedPrecision)" style="width: 100px"
                                   @change="markUnitDirty(r)" />
                </td>
                <td>
                  <el-select v-if="r.unitOptions" v-model="r.historyUnit" size="small" style="width: 140px"
                             @change="markUnitDirty(r)">
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
          <div v-else class="asm-empty">{{ unitDevice ? '该设备暂无可配单位的数值参数' : '选择设备后编辑其参数的监控/历史显示单位与小数位' }}</div>
          <div class="asm-unit-actions">
            <el-button type="primary" size="small" :loading="unitSaving"
                       :disabled="!unitDevice || !unitRows.some((r) => r._dirty)" @click="saveUnitPrefs">
              保存
            </el-button>
          </div>
          <div class="asm-hint">
            单位候选与监控页「⚙ 单位设置」同源（同类全部单位 + 气态跨类；跨类不可换算目标选中后按现有语义显原生）。
            小数位留空 = 不修改当前配置，仅监控页生效（历史页出口不修约）。
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（config），宿主 keep-alive 按组件名匹配缓存。

import { getConfigStat, getConfigUnit, getSnapshot, putConfigStat, putConfigUnit } from '@/api/asm'

// 与后端 AsmGranularityMask 同定义（bit0=分 / bit1=5分 / bit2=时）
const MASKS = [
  { value: 1, label: '1（分）' },
  { value: 2, label: '2（5分）' },
  { value: 3, label: '3（分+5分）' },
  { value: 4, label: '4（时）' },
  { value: 5, label: '5（分+时）' },
  { value: 6, label: '6（5分+时）' },
  { value: 7, label: '7（全开）' },
]
const MODES = ['FRONT', 'BACK', 'BOTH']

// 中文拼音比较（与监控页 AsmMonitorHome 同口径；Chromium Intl 支持 zh-Hans-CN 拼音 collation）
function pinyinCompare(a, b) {
  return String(a).localeCompare(String(b), 'zh-Hans-CN')
}

export default {
  name: 'config',
  data() {
    return {
      MASKS, MODES,
      activeTab: 'stat',
      // 统计物化 tab：config_stat 行 + 顶部过滤（设备 uniqueId / 参数关键字命中 attr_label 或 attrId，空=不过滤）
      statRows: [],
      statFilterUid: '',
      statFilterKeyword: '',
      // 单位偏好 tab：snapshot 设备行（参数中文名/单位候选/生效值）+ config_unit 已存 MONITOR/HISTORY 偏好回显
      snapDevices: [],
      unitPrefs: [],
      unitDeviceUid: null,
      unitRows: [],
      loading: false,
      unitSaving: false,
    }
  },
  computed: {
    statDevices() {
      const out = []
      const seen = new Set()
      for (const r of this.statRows) {
        if (seen.has(r.logicDeviceUniqueId)) continue
        seen.add(r.logicDeviceUniqueId)
        out.push({ uid: r.logicDeviceUniqueId, label: r.device_label || r.logicDeviceUniqueId })
      }
      return out
    },
    filteredStatRows() {
      const kw = this.statFilterKeyword.trim().toLowerCase()
      return this.statRows.filter((r) => {
        if (this.statFilterUid && r.logicDeviceUniqueId !== this.statFilterUid) return false
        if (!kw) return true
        const label = r.attr_label || r.attrId
        return String(label).toLowerCase().includes(kw) || String(r.attrId).toLowerCase().includes(kw)
      })
    },
    // 单位偏好设备候选：仅含数值参数（attrGroup==2）的设备——单位/小数位只对数值行有意义
    unitDevices() {
      return this.snapDevices
        .filter((d) => (d.attrs || []).some((a) => a.attrGroup === 2))
        .map((d) => ({ uid: d.logicDeviceUniqueId, label: d.displayName || d.logicDeviceUniqueId }))
        .sort((a, b) => pinyinCompare(a.label, b.label))
    },
    unitDevice() {
      return this.snapDevices.find((d) => d.logicDeviceUniqueId === this.unitDeviceUid) || null
    },
  },
  watch: {
    // 换设备：重置行编辑态（按新设备 snapshot 行 + 已存偏好重建回显值）
    unitDeviceUid() {
      this.syncUnitRows()
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    markDirty(row) { row._dirty = true },
    markUnitDirty(row) { row._dirty = true },
    // 三路并行取数按结果独立消费：config_stat/config-unit 与 snapshot 分属不同权限域
    // （asm-monitor:config:read vs asm-monitor:monitor:list），一侧 403 不拖 blank 另一 tab；
    // 失败响应已由宿主 axios 拦截器 toast，页面侧该 tab 保持空数据即可见。
    async load() {
      this.loading = true
      try {
        const [stat, unitPref, snap] = await Promise.allSettled([getConfigStat(), getConfigUnit(), getSnapshot('custom')])
        if (stat.status === 'fulfilled') {
          this.statRows = ((stat.value && stat.value.data) || []).map((r) => ({ ...r, _dirty: false, _saving: false }))
        }
        if (unitPref.status === 'fulfilled') {
          this.unitPrefs = (unitPref.value && unitPref.value.data) || []
        }
        if (snap.status === 'fulfilled') {
          this.snapDevices = (snap.value && snap.value.data) || []
        }
        this.syncUnitRows()
      } finally {
        this.loading = false
      }
    },
    async saveStat(row) {
      row._saving = true
      try {
        const res = await putConfigStat({
          logicDeviceUniqueId: row.logicDeviceUniqueId,
          attrId: row.attrId,
          enabled: row.enabled,
          granularityMask: row.granularityMask,
          materializationMode: row.materializationMode,
        })
        row._dirty = false
        this.$message && this.$message.success((res && res.msg) || '配置已保存（不回溯历史）')
      } finally {
        row._saving = false
      }
    },
    // 行编辑态初始化（镜像监控页抽屉 syncUnitFormRows）：仅数值行（attrGroup==2）可编辑。
    // MONITOR 回显 = config_unit 已存偏好（无行 = snapshot unitKey，即解析链兜底后的当前生效单位）；
    // HISTORY 回显 = 已存偏好（无行 = ''，即显原生）。
    syncUnitRows() {
      const dev = this.unitDevice
      if (!dev) { this.unitRows = []; return }
      const prefs = new Map(this.unitPrefs
        .filter((p) => p.logicDeviceUniqueId === dev.logicDeviceUniqueId)
        .map((p) => [p.attrId + '#' + p.purpose, p]))
      this.unitRows = (dev.attrs || [])
        .filter((a) => a.attrGroup === 2)
        .sort((a, b) => pinyinCompare(a.displayName || a.attrId, b.displayName || b.attrId))
        .map((a) => {
          const monitor = prefs.get(a.attrId + '#MONITOR')
          const history = prefs.get(a.attrId + '#HISTORY')
          return {
            attrId: a.attrId,
            displayName: a.displayName || a.attrId,
            unitOptions: a.unitOptions || null,
            unitSymbol: a.unit,
            monitorUnit: monitor ? (monitor.unit || '') : (a.unitKey != null ? a.unitKey : ''),
            monitorPrecision: null,
            resolvedPrecision: a.displayPrecision,
            historyUnit: history ? (history.unit || '') : '',
            _dirty: false,
          }
        })
    },
    // 保存：与监控页抽屉同一端点 PUT /asm-monitor/config-unit（一处保存两处生效）。
    // 仅写 dirty 行：MONITOR 带小数位（空=不改，后端 upsert coalesce）；HISTORY 仅单位——
    // 小数位历史出口不消费（AsmUnitContract.monitorDisplayPrecision 契约），不写无效字段。
    async saveUnitPrefs() {
      const dev = this.unitDevice
      if (!dev) return
      this.unitSaving = true
      try {
        for (const r of this.unitRows.filter((x) => x._dirty)) {
          await putConfigUnit({
            logicDeviceUniqueId: dev.logicDeviceUniqueId,
            attrId: r.attrId,
            purpose: 'MONITOR',
            unit: r.monitorUnit || null,
            displayPrecision: r.monitorPrecision,
          })
          await putConfigUnit({
            logicDeviceUniqueId: dev.logicDeviceUniqueId,
            attrId: r.attrId,
            purpose: 'HISTORY',
            unit: r.historyUnit || null,
          })
        }
        this.$message && this.$message.success('单位偏好已保存（监控/历史读出口即时生效）')
        const unitRes = await getConfigUnit()
        this.unitPrefs = (unitRes && unitRes.data) || []
        this.syncUnitRows()
      } finally {
        this.unitSaving = false
      }
    },
  },
}
</script>

<style scoped>
.asm-page { padding: 12px; }
.asm-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.asm-title { font-size: 16px; font-weight: 600; }
.asm-filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.asm-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.asm-table th, .asm-table td { border-bottom: 1px solid #ebeef5; padding: 6px 8px; text-align: left; }
.asm-table th { background: #fafafa; color: #606266; }
.asm-muted { color: #c0c4cc; }
.asm-empty { color: #909399; padding: 12px; text-align: center; }
.asm-unit-actions { margin-top: 10px; }
.asm-hint { margin-top: 8px; font-size: 12px; color: #909399; line-height: 1.6; }
</style>
