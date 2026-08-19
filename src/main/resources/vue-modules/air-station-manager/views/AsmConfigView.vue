<template>
  <!--
    聚合单位配置（路由 name=config）：可编辑。
    上表 config_stat（GET/PUT /asm-monitor/config-stat）：enabled/粒度掩码/物化范围，逐行编辑保存；
    写语义=「配置不回溯历史」——下个物化 tick 按新配置走，不触发重算（alert + 保存响应 msg 注明）。
    下表 config_unit（GET/PUT /asm-monitor/config-unit）：MONITOR/HISTORY 偏好单位（STORAGE 行是
    seed 域端点不开放）；写后后端失效单位缓存即时生效。
  -->
  <div class="asm-page">
    <div class="asm-toolbar"><span class="asm-title">聚合单位配置</span>
      <button class="asm-btn" :disabled="loading" @click="load">刷新</button>
    </div>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="聚合配置不回溯历史：保存后下个物化 tick 按新配置生效，不触发历史重算/回补；单位偏好保存后即时生效（读出口缓存已失效）"
      style="margin-bottom: 12px"
    />

    <div class="asm-section-title">聚合配置（config_stat）</div>
    <el-table :data="statRows" v-loading="loading" size="small" border>
      <el-table-column prop="logicDeviceUniqueId" label="站房设备" min-width="180" show-overflow-tooltip />
      <el-table-column prop="attrId" label="参数" min-width="130" />
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
          <el-button size="mini" type="primary" :disabled="!row._dirty || row._saving" :loading="row._saving" @click="saveStat(row)">
            保存
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="asm-section-title">单位偏好（config_unit，MONITOR/HISTORY）</div>
    <div class="asm-row">
      <span class="asm-label">设备</span>
      <select v-model="unitForm.uid">
        <option value="">请选择</option>
        <option v-for="u in uids" :key="u" :value="u">{{ u }}</option>
      </select>
      <span class="asm-label">参数</span>
      <select v-model="unitForm.attrId">
        <option value="">请选择</option>
        <option v-for="a in currentAttrIds" :key="a" :value="a">{{ a }}</option>
      </select>
      <span class="asm-label">用途</span>
      <select v-model="unitForm.purpose">
        <option value="MONITOR">MONITOR（监控卡片）</option>
        <option value="HISTORY">HISTORY（历史查询）</option>
      </select>
      <span class="asm-label">单位</span>
      <input v-model="unitForm.unit" placeholder="如 ug/m3；留空=显原生" style="width: 160px" />
      <button class="asm-btn primary" :disabled="!unitForm.uid || !unitForm.attrId || unitSaving" @click="saveUnit">
        {{ unitSaving ? '保存中…' : '保存单位偏好' }}
      </button>
    </div>
    <el-table :data="unitRows" v-loading="loading" size="small" border>
      <el-table-column prop="logicDeviceUniqueId" label="站房设备" min-width="180" show-overflow-tooltip />
      <el-table-column prop="attrId" label="参数" min-width="130" />
      <el-table-column prop="purpose" label="用途" width="110" />
      <el-table-column label="单位" min-width="140">
        <template #default="{ row }">{{ row.unit || '(显原生/无量纲)' }}</template>
      </el-table-column>
      <el-table-column prop="updatedBy" label="最近修改人" width="120" />
    </el-table>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（config），宿主 keep-alive 按组件名匹配缓存。

import { getConfigStat, getConfigUnit, putConfigStat, putConfigUnit } from '@/api/asm'

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

export default {
  name: 'config',
  data() {
    return {
      MASKS, MODES,
      statRows: [],
      unitRows: [],
      unitForm: { uid: '', attrId: '', purpose: 'MONITOR', unit: '' },
      loading: false,
      unitSaving: false,
    }
  },
  computed: {
    uids() {
      return [...new Set(this.statRows.map((r) => r.logicDeviceUniqueId))]
    },
    currentAttrIds() {
      return [...new Set(this.statRows
        .filter((r) => r.logicDeviceUniqueId === this.unitForm.uid)
        .map((r) => r.attrId))]
    },
  },
  watch: {
    'unitForm.uid'() {
      this.unitForm.attrId = ''
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    markDirty(row) { row._dirty = true },
    async load() {
      this.loading = true
      try {
        const [statRes, unitRes] = await Promise.all([getConfigStat(), getConfigUnit()])
        this.statRows = ((statRes && statRes.data) || []).map((r) => ({ ...r, _dirty: false, _saving: false }))
        this.unitRows = (unitRes && unitRes.data) || []
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
    async saveUnit() {
      this.unitSaving = true
      try {
        const res = await putConfigUnit({
          logicDeviceUniqueId: this.unitForm.uid,
          attrId: this.unitForm.attrId,
          purpose: this.unitForm.purpose,
          unit: this.unitForm.unit || null,
        })
        this.$message && this.$message.success((res && res.msg) || '单位偏好已保存')
        this.unitForm.unit = ''
        const unitRes = await getConfigUnit()
        this.unitRows = (unitRes && unitRes.data) || []
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
.asm-section-title { font-size: 14px; font-weight: 600; color: #303133; margin: 14px 0 8px; }
.asm-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.asm-label { color: #606266; font-size: 13px; }
.asm-btn { padding: 4px 14px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; cursor: pointer; }
.asm-btn.primary { background: #409eff; color: #fff; }
.asm-btn:disabled { opacity: .6; }
</style>
