<template>
  <!--
    报警规则（路由 name=alarm_rule）：规则列表 + 结构化编辑弹窗。
    setting_content JSON 契约（兼容 env_alarm_settings）：name/enabled/configurable/device_info/
    configs[type=range|number|duration|setting]/check/severity。弹窗按 config 类型逐项编辑，
    提交前拼回 JSON 字符串——后端 AsmAlarmRuleDefinition.parse 全量校验，非法抛 400 语义（toast）。
  -->
  <div class="asm-page">
    <div class="asm-toolbar">
      <span class="asm-title">报警规则</span>
      <button class="asm-btn primary" @click="openEdit(null)">新增规则</button>
    </div>

    <el-table :data="rules" v-loading="loading" size="small" border>
      <!-- alarmType 是系统稳定标识（英文/数字、全局唯一、供其他集成查询）：列名用「报警标识」+ 头部悬浮说明，
           mono 展示与业务文本区分（运维拷贝/比对场景）。系统预置标识不可修改（后端 400 拒绝）。 -->
      <el-table-column min-width="170">
        <template #header>
          报警标识
          <el-tooltip content="系统稳定标识（英文/数字、全局唯一、供其他集成查询）；系统预置标识不可修改" placement="top">
            <span class="asm-help">?</span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <span class="asm-mono">{{ row.alarmType }}</span>
        </template>
      </el-table-column>
      <el-table-column label="规则名" min-width="140">
        <template #default="{ row }">{{ parsed(row).name }}</template>
      </el-table-column>
      <el-table-column label="严重级别" width="90">
        <template #default="{ row }">
          <el-tag :type="severityTag(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch :model-value="parsed(row).enabled" @change="(v) => toggleEnabled(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="适用设备/参数" min-width="280">
        <template #default="{ row }">
          <!-- deviceLabels 契约：[{slot:槽中文名, attrs:[参数名]}]，每设备一行「槽中文名（参数1、参数2）」；
               缺失时回退 device_info 的 uniqueId[attrId] 显示（过渡兼容） -->
          <div v-for="(line, i) in deviceLines(row)" :key="i">{{ line }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" />
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 760px + body 限高滚动：判定条件区改名/加灰字说明后纵向变长，弹窗本体不随内容无限拉伸 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑规则' : '新增规则'" width="760px">
      <div class="asm-dialog-scroll">
      <el-form label-width="110px" size="small">
        <!-- 标识可编辑（含编辑态）：预置改标识/重复标识由后端 400 拒绝，前端只透出错误文案（ElMessage） -->
        <el-form-item label="标识" required>
          <el-input v-model="form.alarmType" placeholder="英文/数字，全局唯一；系统预置不可修改" />
        </el-form-item>
        <el-form-item label="规则名" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="严重级别">
          <el-select v-model="form.severity" style="width: 120px">
            <el-option label="普通" value="0" /><el-option label="重要" value="1" /><el-option label="紧急" value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="设备/参数" required>
          <div class="asm-devinfo">
            <div v-for="(d, i) in form.devices" :key="i" class="asm-devinfo-row">
              <el-input v-model="d.uid" placeholder="设备 uniqueId" style="width: 240px" />
              <el-input v-model="d.attrs" placeholder="attrId,逗号分隔" style="width: 240px" />
              <el-button link type="danger" @click="form.devices.splice(i, 1)">删除</el-button>
            </div>
            <el-button link type="primary" @click="form.devices.push({ uid: '', attrs: '' })">+ 添加设备</el-button>
          </div>
        </el-form-item>

        <!-- 判定条件：每项勾选启用 + 灰字说明（说明=该项的触发语义，勾选前可读） -->
        <el-divider content-position="left">判定条件（勾选启用）</el-divider>
        <el-form-item label="量程越限">
          <el-checkbox v-model="cfg.range.on" />
          <template v-if="cfg.range.on">
            <el-input-number v-model="cfg.range.min" :controls="false" placeholder="min" style="width: 120px" />
            <span>~</span>
            <el-input-number v-model="cfg.range.max" :controls="false" placeholder="max" style="width: 120px" />
          </template>
          <div class="asm-cfg-hint">值超出 [下限,上限] 判为越限</div>
        </el-form-item>
        <el-form-item label="持续时长">
          <el-checkbox v-model="cfg.duration.on" />
          <template v-if="cfg.duration.on">
            <el-input-number v-model="cfg.duration.minutes" :min="1" :controls="false" style="width: 120px" />
            <span>分钟</span>
          </template>
          <div class="asm-cfg-hint">持续越限 N 分钟才触发</div>
        </el-form-item>
        <el-form-item label="阈值">
          <el-checkbox v-model="cfg.number.on" />
          <template v-if="cfg.number.on">
            <el-input v-model="cfg.number.cls" placeholder="class（attrId，如 power）" style="width: 180px" />
            <el-input-number v-model="cfg.number.value" :controls="false" style="width: 120px" />
            <el-select v-model="cfg.number.compare" style="width: 100px">
              <el-option label="超过" value="gt" /><el-option label="低于" value="lt" />
            </el-select>
          </template>
          <div class="asm-cfg-hint">指定参数的值超过/低于阈值即时触发</div>
        </el-form-item>
        <el-form-item label="联动设置">
          <el-checkbox v-model="cfg.setting.on" />
          <template v-if="cfg.setting.on">
            <el-input v-model="cfg.setting.deviceId" placeholder="联动 device_id" style="width: 180px" />
            <el-input v-model="cfg.setting.paramId" placeholder="联动 param_id" style="width: 140px" />
            <el-input v-model="cfg.setting.value" placeholder="写值 value" style="width: 120px" />
          </template>
          <div class="asm-cfg-hint">报警触发时向联动设备的指定参数写入设定值</div>
        </el-form-item>
        <el-form-item label="巡检判定">
          <el-select v-model="form.check" clearable placeholder="缺省按已勾选条件自动推断" style="width: 220px">
            <el-option v-for="c in CHECKS" :key="c" :label="c" :value="c" />
          </el-select>
          <div class="asm-cfg-hint">显式指定判定方式；缺省按已勾选条件自动推断（量程→RANGE_DURATION、阈值→INSTANT_THRESHOLD/POWER）</div>
        </el-form-item>
      </el-form>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（alarm_rule），宿主 keep-alive 按组件名匹配缓存。

import { ElMessage } from 'element-plus'
import { listAlarmRules, addAlarmRule, editAlarmRule, removeAlarmRule } from '@/api/asm'

const CHECKS = ['RANGE_DURATION', 'INSTANT_THRESHOLD', 'POWER', 'STATUS_MATCH']

function emptyForm() {
  return {
    id: null, alarmType: '', name: '', severity: '0', enabled: true, check: '',
    devices: [{ uid: '', attrs: '' }],
  }
}

function emptyCfg() {
  return {
    range: { on: false, min: null, max: null },
    duration: { on: false, minutes: null },
    number: { on: false, cls: '', value: null, compare: 'gt' },
    setting: { on: false, deviceId: '', paramId: '', value: '' },
  }
}

export default {
  name: 'alarm_rule',
  data() {
    return { CHECKS, rules: [], loading: false, dialogVisible: false, saving: false, form: emptyForm(), cfg: emptyCfg() }
  },
  mounted() {
    this.load()
  },
  methods: {
    parsed(row) {
      try {
        const c = JSON.parse(row.settingContent)
        return { name: c.name || '-', enabled: !!c.enabled, content: c }
      } catch (e) {
        return { name: '(非法 JSON)', enabled: false, content: {} }
      }
    },
    severityLabel(s) {
      return { 0: '普通', 1: '重要', 2: '紧急' }[s] || s
    },
    severityTag(s) {
      return { 0: 'info', 1: 'warning', 2: 'danger' }[s] || 'info'
    },
    // 适用设备/参数列文案行：优先 deviceLabels 契约（槽中文名（参数1、参数2）），
    // 缺失回退 device_info uniqueId[attrId]（过渡兼容）；空两者皆无 → ['-'] 占位
    deviceLines(row) {
      if (Array.isArray(row.deviceLabels) && row.deviceLabels.length) {
        return row.deviceLabels.map((d) => `${d.slot || '-'}（${(d.attrs || []).join('、')}）`)
      }
      const di = this.parsed(row).content.device_info || {}
      const lines = Object.entries(di).map(([uid, attrs]) => `${uid}[${attrs.join(',')}]`)
      return lines.length ? lines : ['-']
    },
    async load() {
      this.loading = true
      try {
        this.rules = await listAlarmRules()
      } finally {
        this.loading = false
      }
    },
    openEdit(row) {
      this.cfg = emptyCfg()
      if (!row) {
        this.form = emptyForm()
      } else {
        const c = this.parsed(row).content
        this.form = {
          id: row.id, alarmType: row.alarmType, name: c.name || '', severity: row.severity || '0',
          enabled: !!c.enabled, check: c.check || '',
          devices: Object.entries(c.device_info || {}).map(([uid, attrs]) => ({ uid, attrs: attrs.join(',') })),
        }
        for (const cfgItem of c.configs || []) {
          if (cfgItem.type === 'range') {
            this.cfg.range = { on: true, min: cfgItem.value[0], max: cfgItem.value[1] }
          } else if (cfgItem.type === 'duration') {
            this.cfg.duration = { on: true, minutes: cfgItem.value }
          } else if (cfgItem.type === 'number') {
            this.cfg.number = { on: true, cls: cfgItem.class, value: cfgItem.value, compare: cfgItem.compare || 'gt' }
          } else if (cfgItem.type === 'setting') {
            this.cfg.setting = { on: true, deviceId: cfgItem.device_id, paramId: cfgItem.param_id, value: String(cfgItem.value) }
          }
        }
      }
      this.dialogVisible = true
    },
    buildContent() {
      const content = { name: this.form.name, enabled: this.form.enabled, configurable: true }
      const deviceInfo = {}
      for (const d of this.form.devices) {
        const uid = (d.uid || '').trim()
        const attrs = (d.attrs || '').split(',').map((s) => s.trim()).filter(Boolean)
        if (uid && attrs.length) deviceInfo[uid] = attrs
      }
      content.device_info = deviceInfo
      const configs = []
      if (this.cfg.range.on) configs.push({ type: 'range', value: [this.cfg.range.min, this.cfg.range.max] })
      if (this.cfg.duration.on) configs.push({ type: 'duration', value: this.cfg.duration.minutes })
      if (this.cfg.number.on) {
        configs.push({ type: 'number', class: this.cfg.number.cls, value: this.cfg.number.value, compare: this.cfg.number.compare })
      }
      if (this.cfg.setting.on) {
        configs.push({ type: 'setting', device_id: this.cfg.setting.deviceId, param_id: this.cfg.setting.paramId, value: this.cfg.setting.value })
      }
      content.configs = configs
      if (this.form.check) content.check = this.form.check
      return content
    },
    async save() {
      const row = {
        id: this.form.id || undefined,
        alarmType: this.form.alarmType,
        severity: this.form.severity,
        settingContent: JSON.stringify(this.buildContent()),
        sort: 0,
      }
      this.saving = true
      try {
        await (row.id ? editAlarmRule(row) : addAlarmRule(row))
        this.dialogVisible = false
        await this.load()
      } catch (e) {
        // 后端 400 带文案（预置改标识/重复标识/parse 校验失败）——前端只透出，不再前端拦截
        ElMessage.error((e && e.message) || '保存失败（校验不通过）')
        throw e
      } finally {
        this.saving = false
      }
    },
    async toggleEnabled(row, enabled) {
      const c = this.parsed(row).content
      c.enabled = enabled
      await editAlarmRule({ id: row.id, alarmType: row.alarmType, severity: row.severity, settingContent: JSON.stringify(c), sort: row.sort })
      await this.load()
    },
    async remove(row) {
      await removeAlarmRule(row.id)
      await this.load()
    },
  },
}
</script>

<style scoped>
.asm-page { padding: 12px; }
.asm-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.asm-title { font-size: 16px; font-weight: 600; }
.asm-btn { padding: 4px 14px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; cursor: pointer; }
.asm-btn.primary { background: #409eff; color: #fff; }
.asm-devinfo-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
/* 表头「?」悬浮说明圆钮（el-tooltip 触发器） */
.asm-help { display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 14px;
  margin-left: 4px; border: 1px solid #c0c4cc; border-radius: 50%; color: #909399; font-size: 11px;
  line-height: 1; cursor: help; vertical-align: middle; }
/* 系统标识 mono 展示（与 StationDeviceDetail .sys-val 同一字栈） */
.asm-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
/* 判定条件每项灰字说明：占满整行换行到控件下方（el-form-item__content 是 flex wrap 容器） */
.asm-cfg-hint { width: 100%; color: #909399; font-size: 12px; line-height: 1.5; }
/* 弹窗 body 限高滚动，弹窗本体不随判定条件区无限拉伸 */
.asm-dialog-scroll { max-height: 62vh; overflow-y: auto; padding-right: 4px; }
</style>
