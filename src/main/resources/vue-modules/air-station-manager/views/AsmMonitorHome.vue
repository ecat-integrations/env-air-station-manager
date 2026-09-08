<template>
  <!--
    站房设备总览（路由 name=monitor）：分组瓦片墙 + 详情抽屉。
    数据流：onMounted 拉 GET /asm-monitor/snapshot?unit=standard|custom 全量初始化（右上角显示单位切换，镜像 ADM）→ SSE /asm-monitor/stream
    （device.data.update 具名帧）per-attr 增量 patch 瓦片与抽屉；断连显示重连横幅（客户端自管指数退避）。
    无来源（LIVE/RAW）列、无完整时间戳（抽屉内仅相对时间）；单位为后端符号化后的符号（°C/V）。
  -->
  <div class="asm-page">
    <div class="asm-toolbar">
      <span class="asm-title">站房设备总览</span>
      <span class="asm-hint">{{ sseConnected ? 'SSE 实时推送中' : '等待实时连接…' }}</span>
      <el-button size="small" :disabled="loading" @click="load">手动刷新</el-button>
      <!-- 单位双模式切换（镜像 ADM AdmUnitSwitcher）：standard=STANDARD 行标准口径 / custom=MONITOR 偏好；
           切换即重拉 snapshot（后端按 unit 换算，前端零换算），选择存 localStorage 刷新恢复 -->
      <span class="asm-unit-switcher">
        <span class="asm-unit-label">显示单位:</span>
        <!-- el-radio-group 仅在值实际变化时触发 @change（「同值不重拉」由组件语义保证），onUnitPick 收新值重拉 -->
        <el-radio-group v-model="unitMode" size="small" @change="onUnitPick">
          <el-radio-button v-for="opt in UNIT_OPTIONS" :key="opt.value" :value="opt.value" :label="opt.label" />
        </el-radio-group>
        <!-- 单位设置抽屉入口：编辑 MONITOR（自定义）行的单位与小数位；「默认」模式读 STANDARD 行不受影响；
             class asm-unit-setting-btn 仅作 e2e 钩子保留（视觉已由 el-button small 接管） -->
        <el-button size="small" class="asm-unit-setting-btn" @click="openUnitSettings">⚙ 单位设置</el-button>
      </span>
    </div>

    <!-- SSE 断连横幅；class asm-banner 仅作 e2e 钩子保留（视觉已由 el-alert 接管，连接健康时整块不渲染） -->
    <el-alert v-if="sseFailed" class="asm-banner" type="warning" :closable="false" title="连接断开，重连中…" />

    <!-- 状态筛选 chips（sticky 于瓦片墙上方）：计数 computed 派生，SSE patch 改设备 online/alarm 后计数自然响应；
         加载中计数显 '—' 并禁点——未知 ≠ 0，杜绝「全部(0)」被误读成没数据 -->
    <div class="asm-chips">
      <button v-for="c in chips" :key="c.key" type="button" class="asm-chip"
              :class="[c.key, { active: filterStatus === c.key }]" :disabled="loading" @click="filterStatus = c.key">
        {{ c.label }}({{ loading ? '—' : c.count }})
      </button>
    </div>

    <div v-loading="loading" class="asm-groups">
      <div v-if="!loading && !devices.length" class="asm-empty">暂无站房设备（未创建或未绑定 logicdevice_station 设备）</div>

      <div v-for="g in groups" :key="g.key" class="asm-group">
        <div class="asm-group-head">
          <span class="asm-group-name">{{ g.name }}</span>
          <span class="asm-group-count">{{ g.devices.length }}</span>
        </div>
        <div class="asm-tiles">
          <div v-for="d in g.devices" :key="d.logicDeviceUniqueId" class="asm-tile" @click="openDrawer(d)">
            <div class="asm-tile-head">
              <span class="asm-tile-name">{{ d.displayName || d.logicDeviceUniqueId }}</span>
              <!-- 圆点三色：红=报警（deviceAlarmBadges 并集非空，ADM cardUnionStatuses 同构）> 灰=离线 > 绿=在线；
                   报警态经 SSE 帧只覆 attr 级字段与 episode 侧标志，结构性杜绝「帧 alarm=false 覆盖 snapshot 报警态」 -->
              <span class="asm-dot" :class="dotClass(d)" />
              <span v-if="!d.online" class="asm-offline-text">{{ offlineText(d) }}</span>
            </div>
            <div v-for="p in coreParams(d)" :key="p.attrId" class="asm-tile-param">
              <span class="asm-param-name">{{ p.displayName || p.attrId }}</span>
              <span class="asm-param-value">{{ attrValue(p) }}<small v-if="p.unit" class="asm-unit">{{ p.unit }}</small></span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-drawer v-model="drawerVisible" :title="drawerDevice ? (drawerDevice.displayName || drawerDevice.logicDeviceUniqueId) : ''" size="480px">
      <div v-if="drawerDevice" class="asm-drawer-body">
        <div class="asm-drawer-meta">
          <span class="asm-dot" :class="dotClass(drawerDevice)" />
          <span v-if="drawerDevice.online">在线</span>
          <span v-else class="asm-offline-text">离线 {{ offlineText(drawerDevice) }}</span>
          <!-- 设备控制入口：仅 DM 配置的可控设备（空调/灯光/排风扇/门禁/采样管/稳压电源）显示 -->
          <el-button v-if="isControllable(drawerDevice)" type="primary" size="small" class="asm-drawer-control-btn"
                  data-asm="drawer-control-btn" @click="goDeviceControl(drawerDevice)">设备控制</el-button>
        </div>
        <!-- 设备级状态条：仅在有报警或离线时出现（在线且无报警时隐藏，保持抽屉简洁）；
             报警徽章并集复用 deviceAlarmBadges，按档 tier-danger/tier-warning 渲染 -->
        <div v-if="deviceAlarmBadges(drawerDevice).length || !drawerDevice.online" class="asm-drawer-statusbar">
          <span v-for="b in deviceAlarmBadges(drawerDevice)" :key="b.text" class="asm-badge" :class="'tier-' + b.tier">{{ b.text }}</span>
          <span v-if="!drawerDevice.online" class="asm-offline-text">离线 {{ offlineText(drawerDevice) }}</span>
        </div>
        <table class="asm-table">
          <thead>
            <tr><th>参数</th><th>当前值</th><th>状态</th><th>更新</th></tr>
          </thead>
          <tbody>
            <tr v-for="a in drawerAttrs" :key="a.attrId">
              <td>{{ a.displayName || a.attrId }}</td>
              <td :style="{ color: statusColor(a.status) }">{{ attrValue(a) }}<small v-if="a.unit" class="asm-unit">{{ a.unit }}</small></td>
              <td><span v-if="a.statusName" class="asm-badge" :class="'tier-' + statusTier(a.status)">{{ a.statusName }}</span><span v-else class="asm-muted">-</span></td>
              <td class="asm-muted">{{ relativeTime(a.updateTime) }}</td>
            </tr>
            <tr v-if="!drawerDevice.attrs.length"><td colspan="4" class="asm-empty">该设备暂无属性数据</td></tr>
          </tbody>
        </table>
      </div>
    </el-drawer>

    <!-- 单位设置抽屉：编辑 MONITOR（自定义）行（单位下拉含同类+气态跨类候选；小数位仅监控页生效，历史页不动）。
         「默认」模式下瓦片不受影响（读 STANDARD 行），仅在「自定义」模式实时反映（后端写后已失效缓存）。 -->
    <el-drawer v-model="unitDrawerVisible" title="单位设置（自定义模式）" size="640px">
      <div class="asm-unit-settings">
        <div class="asm-unit-settings-bar">
          <span class="asm-unit-label">设备:</span>
          <el-select v-model="unitSettingUid" placeholder="选择设备" size="default" style="width: 260px" data-asm="unit-device-select">
            <el-option-group v-for="g in unitDeviceGroups" :key="g.key" :label="g.name">
              <el-option v-for="d in g.devices" :key="d.logicDeviceUniqueId"
                         :value="d.logicDeviceUniqueId" :label="d.displayName || d.logicDeviceUniqueId" />
            </el-option-group>
          </el-select>
        </div>
        <table v-if="unitSettingDevice" class="asm-table">
          <thead>
            <tr><th>参数</th><th>当前值</th><th>单位</th><th>小数位</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in unitSettingRows" :key="r.attrId">
              <td>{{ r.displayName || r.attrId }}</td>
              <td>{{ attrValue(r) }}<small v-if="r.unit" class="asm-unit">{{ r.unit }}</small></td>
              <td>
                <el-select v-if="r.editable && r.unitOptions" v-model="r.formUnit" size="small"
                           style="width: 130px" :data-asm="'unit-select-' + r.attrId">
                  <el-option-group v-for="g in r.unitOptions" :key="g.classLabel" :label="g.classLabel">
                    <el-option v-for="u in g.units" :key="u.key" :value="u.key" :label="u.symbol" />
                  </el-option-group>
                </el-select>
                <span v-else class="asm-muted">{{ r.unit || '-' }}</span>
              </td>
              <td>
                <el-input-number v-if="r.editable" v-model="r.formPrecision" :min="0" :max="6" size="small"
                                 :placeholder="String(r.displayPrecision)" style="width: 100px"
                                 :data-asm="'precision-' + r.attrId" />
                <span v-else class="asm-muted">-</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="asm-empty">选择设备后编辑其参数单位与小数位</div>
        <div class="asm-unit-settings-actions">
          <el-button type="primary" size="small" :loading="unitSaving" :disabled="!unitSettingDevice" data-asm="unit-save"
                     @click="saveUnitSettings">保存</el-button>
        </div>
        <div class="asm-unit-settings-hint">
          单位候选 = 同类全部单位 + 气态跨类（mg/m³↔ppm）；跨类不可换算目标选中后按现有语义显原生。
          小数位留空 = 不修改当前配置。
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script>
// keep-alive 契约：Options API 组件 name 必须等于路由 name（monitor），宿主 keep-alive 按组件名匹配缓存。
import { getSnapshot, putConfigUnit } from '@/api/asm'
import { isControllableUid } from '../control/constants'
import { DEVICE_GROUPS, catalogOf } from '@/utils/deviceCatalog'
import { AsmMonitorSseClient } from '../sse/AsmMonitorSseClient'
import { statusTier, statusColor } from '../utils/statusBadge'

// 单位模式恢复（对齐 ADM loadUnit）：非 standard/custom 值重置 standard（不映射旧值）；隐私模式默认 standard。
function loadUnitMode() {
  try {
    const v = localStorage.getItem('asm-monitor-unit')
    return (v === 'standard' || v === 'custom') ? v : 'standard'
  } catch (e) {
    return 'standard'
  }
}

// 中文拼音比较（与后端 Collator zh 同口径；Chromium Intl 支持 zh-Hans-CN 拼音 collation）
function pinyinCompare(a, b) {
  return String(a).localeCompare(String(b), 'zh-Hans-CN')
}

export default {
  name: 'monitor',
  data() {
    return {
      devices: [],
      loading: false,
      // 单位模式（standard/custom，对齐 ADM）：localStorage 恢复上次选择，首次默认 standard
      unitMode: loadUnitMode(),
      UNIT_OPTIONS: [
        // 显示文字「默认」（=STANDARD 行标准口径）；localStorage 存值仍是 standard（老用户已存选择直接恢复）
        { value: 'standard', label: '默认' },
        { value: 'custom', label: '自定义' },
      ],
      drawerVisible: false,
      drawerUid: null,
      // 瓦片墙状态筛选：all=全部 / offline=离线(!online) / alarm=报警(deviceAlarmBadges 并集非空)
      filterStatus: 'all',
      sseConnected: false,
      sseFailed: false,
      sseClient: null,
      // 相对时间/离线时长唯一时钟（1s tick 驱动「N秒前」秒级平滑递增；SSE patch 只改数据行，
      // 时间文本仅随本 tick 重算——避免 SSE 帧与粗粒度 tick 双源触发导致的非线性跳变）
      nowMs: Date.now(),
      nowTimer: null,
      // 单位设置抽屉状态：选中设备 uid + 行编辑态（formUnit/formPrecision 仅数值行可编辑）
      unitDrawerVisible: false,
      unitSettingUid: null,
      unitFormRows: [],
      unitSaving: false,
    }
  },
  watch: {
    // 单位设置抽屉换设备：重置行编辑态（formUnit=新设备当前 unitKey）
    unitSettingUid() {
      this.syncUnitFormRows()
    },
  },
  computed: {
    // 筛选 chips：计数 computed 派生（离线=!online / 报警=deviceAlarmBadges 并集非空），SSE patch 后自然响应
    chips() {
      return [
        { key: 'all', label: '全部', count: this.devices.length },
        { key: 'offline', label: '离线', count: this.devices.filter(d => !d.online).length },
        { key: 'alarm', label: '报警', count: this.devices.filter(d => this.deviceAlarmBadges(d).length).length },
      ]
    },
    filteredDevices() {
      if (this.filterStatus === 'offline') return this.devices.filter(d => !d.online)
      if (this.filterStatus === 'alarm') return this.devices.filter(d => this.deviceAlarmBadges(d).length)
      return this.devices
    },
    groups() {
      // 组内瓦片按设备中文名拼音序（渲染序治理：后端 snapshot 迭代序=registry 顺序，安防组曾出现
      // 「4智能视频监控系统」排在「1门禁」前；displayName null 回退 uid。纯前端渲染序，后端不动）
      return DEVICE_GROUPS
        .map(g => ({
          ...g,
          devices: this.filteredDevices
            .filter(d => catalogOf(d.logicDeviceUniqueId).group === g.key)
            .sort((a, b) => pinyinCompare(a.displayName || a.logicDeviceUniqueId, b.displayName || b.logicDeviceUniqueId)),
        }))
        .filter(g => g.devices.length)
    },
    drawerDevice() {
      return this.devices.find(d => d.logicDeviceUniqueId === this.drawerUid) || null
    },
    // 抽屉行序（与后端 sortAttrRows 同 key 镜像，保 SSE patch 后不乱序）：分组序 状态类(0)→命令类(1)→数值类(2)
    // （命令=attrId _command 结尾；数值=行有数值；状态=其余），组内 displayName 拼音序 + 「重置*」多音字例外
    // （按 chóng 排组内最前，与后端 ATTR_ORDER 同口径）。瓦片核心参数按 catalog 顺序不受影响。
    // 单位设置抽屉设备下拉：按 catalog 分组、组内中文名拼音序（选项全中文名 displayName，不泄 uid）
    unitDeviceGroups() {
      return DEVICE_GROUPS
        .map(g => ({
          ...g,
          devices: this.devices
            .filter(d => catalogOf(d.logicDeviceUniqueId).group === g.key)
            .sort((a, b) => pinyinCompare(a.displayName || a.logicDeviceUniqueId, b.displayName || b.logicDeviceUniqueId)),
        }))
        .filter(g => g.devices.length)
    },
    unitSettingDevice() {
      return this.devices.find(d => d.logicDeviceUniqueId === this.unitSettingUid) || null
    },
    // 选中设备的可编辑行快照：仅数值类（attrGroup==2）可编辑单位/小数位
    unitSettingRows() {
      return this.unitFormRows
    },
    drawerAttrs() {
      if (!this.drawerDevice) return []
      const groupOf = a => {
        if (a.attrGroup != null) return a.attrGroup   // snapshot 行自带后端分组键（DEF 行值缺席须后端供键）
        return (a.attrId && a.attrId.endsWith('_command')) ? 1 : (typeof a.value === 'number' ? 2 : 0)
      }
      return [...this.drawerDevice.attrs].sort((a, b) => {
        const ga = groupOf(a), gb = groupOf(b)
        if (ga !== gb) return ga - gb
        const na = (a.displayName || '').startsWith('重置')
        const nb = (b.displayName || '').startsWith('重置')
        if (na !== nb) return na ? -1 : 1
        return pinyinCompare(a.displayName || a.attrId, b.displayName || b.attrId)
      })
    },
  },
  mounted() {
    this.load()
    this.startSse()
    this.nowTimer = setInterval(() => { this.nowMs = Date.now() }, 1000)
  },
  activated() {
    // keep-alive 重入：SSE 可能已被 deactivated 停掉，重开
    if (!this.sseClient) this.startSse()
  },
  deactivated() {
    this.stopSse()
  },
  beforeUnmount() {
    this.stopSse()
    if (this.nowTimer) clearInterval(this.nowTimer)
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await getSnapshot(this.unitMode === 'custom' ? 'custom' : undefined)
        this.devices = (res && res.data) || []
      } finally {
        this.loading = false
      }
    },
    // 单位切换：el-radio-group 的 v-model 先更新值再触发 @change（同值点击不触发 change，
    // 「同值不重拉」由组件语义保证）——此处不得再比对 unitMode（v-model 已改，恒等，曾把
    // 全部重拉吞掉致 G6 挂）；持久化后重拉 snapshot（后端按 unit 换算）。
    onUnitPick(value) {
      try { localStorage.setItem('asm-monitor-unit', value) } catch (e) { /* 隐私模式降级内存态 */ }
      this.load()
    },
    startSse() {
      this.sseClient = new AsmMonitorSseClient({
        onUpdate: payload => this.handleSseUpdate(payload),
        onOpen: () => { this.sseConnected = true; this.sseFailed = false },
        onError: () => { this.sseFailed = true },
      })
      this.sseClient.start()
    },
    stopSse() {
      if (this.sseClient) {
        this.sseClient.stop()
        this.sseClient = null
      }
      this.sseConnected = false
    },
    // SSE 增量 patch：一帧 = 一个设备一个 attr 新值，按 (uid, attrId) 原地替换；attr 新增则 push（SSE 帧
    // 可能先于 snapshot 返回到达）。事件到达即设备活性在线（60s 窗内必然满足）。
    // window.__asmSseFrames 计数器供 e2e 断言「收到 ≥1 帧 device.data.update」（网络面板外的确定性证据）。
    handleSseUpdate(payload) {
      if (typeof window !== 'undefined') {
        window.__asmSseFrames = (window.__asmSseFrames || 0) + 1
      }
      const device = this.devices.find(d => d.logicDeviceUniqueId === payload.logicDeviceUniqueId)
      if (!device) return
      // 双值同推（同 ADM 监控页方案）：standard 模式取 standardValue/standardUnit，custom 取 displayValue/unit；
      // 单位模式切换后 snapshot 已同口径重拉，本 patch 与当前模式一致
      const isStandard = this.unitMode === 'standard'
      const row = {
        attrId: payload.attrId,
        displayName: payload.displayName,
        value: isStandard ? payload.standardValue : payload.displayValue,
        valueText: payload.valueText,
        unit: isStandard ? payload.standardUnit : payload.unit,
        updateTime: payload.updateTime,
        statusName: payload.statusName,
        status: payload.status,
      }
      const idx = device.attrs.findIndex(a => a.attrId === payload.attrId)
      if (idx >= 0) device.attrs.splice(idx, 1, row)
      else device.attrs.push(row)  // 新 attr 追加尾部，抽屉经 drawerAttrs 拼音序 computed 重排不乱序
      device.online = true
      device.offlineMs = 0
      // 规则 episode 侧实时维护（帧内 ruleAlarmActive）：驱动卡片徽章 episode 侧；attr 侧报警态随上面
      // row.status patch 自然更新（deviceAlarmBadges 每次渲染对全 attr statuses 重求并集，单侧不再覆另一侧）
      device.ruleAlarmActive = !!payload.ruleAlarmActive
    },
    // 卡片报警徽章并集（ADM cardUnionStatuses 同构）：① 全 attr statuses 中 danger 档（statusBadge ASM_STATUS_TIER）
    // 按 statusName 去重 + ② episode 侧（activeAlarms 明细 ruleName 去重；snapshot 未见但 mid-session 新开的
    // episode 由 SSE 帧 ruleAlarmActive=true 兜底显示「报警」）。空数组=无报警（chips 报警计数同源）。
    deviceAlarmBadges(d) {
      const out = []
      const seen = new Set()
      for (const a of (d.attrs || [])) {
        if (statusTier(a.status) !== 'danger') continue
        const text = a.statusName || a.status
        if (!seen.has(text)) { seen.add(text); out.push({ tier: 'danger', text }) }
      }
      for (const alarm of (d.activeAlarms || [])) {
        const text = alarm.ruleName || alarm.displayName || '报警'
        if (!seen.has(text)) { seen.add(text); out.push({ tier: 'danger', text }) }
      }
      if (d.ruleAlarmActive && !(d.activeAlarms || []).length) {
        out.push({ tier: 'danger', text: '报警' })   // 帧 ruleAlarmActive=true 但 activeAlarms 明细尚未对齐（滞后边界同 ADM）
      }
      return out
    },
    // 瓦片/抽屉圆点三色，优先级 红(报警) > 灰(离线) > 绿(在线)：报警并集非空即红（即使同时离线）
    dotClass(d) {
      if (this.deviceAlarmBadges(d).length) return 'alarm'
      return d.online ? 'on' : 'off'
    },
    // 打开单位设置抽屉：初始化选中设备的行编辑态（formUnit=当前 unitKey 回显、formPrecision 留空=不改）
    openUnitSettings() {
      this.unitDrawerVisible = true
      this.syncUnitFormRows()
    },
    syncUnitFormRows() {
      const dev = this.unitSettingDevice
      if (!dev) { this.unitFormRows = []; return }
      this.unitFormRows = [...dev.attrs]
        .sort((a, b) => {
          const ga = a.attrGroup != null ? a.attrGroup : 0
          const gb = b.attrGroup != null ? b.attrGroup : 0
          if (ga !== gb) return ga - gb
          return pinyinCompare(a.displayName || a.attrId, b.displayName || b.attrId)
        })
        .map(a => ({
          ...a,
          editable: a.attrGroup === 2,
          formUnit: a.unitKey != null ? a.unitKey : '',
          formPrecision: null,
        }))
    },
    // 保存：逐行 PUT config-unit（purpose=MONITOR）；unit 回传候选 key（空=显原生），precision 空不覆盖
    async saveUnitSettings() {
      const dev = this.unitSettingDevice
      if (!dev) return
      this.unitSaving = true
      try {
        for (const r of this.unitFormRows.filter(x => x.editable)) {
          await putConfigUnit({
            logicDeviceUniqueId: dev.logicDeviceUniqueId,
            attrId: r.attrId,
            purpose: 'MONITOR',
            unit: r.formUnit || null,
            displayPrecision: r.formPrecision,
          })
        }
        this.$message && this.$message.success('单位设置已保存（自定义模式下即时生效）')
        // 后端写后已失效缓存；重拉 snapshot 使瓦片/抽屉立即反映（自定义模式）——SSE 后续帧同口径
        if (this.unitMode === 'custom') await this.load()
        this.syncUnitFormRows()
      } finally {
        this.unitSaving = false
      }
    },
    openDrawer(d) {
      this.drawerUid = d.logicDeviceUniqueId
      this.drawerVisible = true
    },
    // 可控判定（设计变更 2026-08-20）：前端常量类型集（control/constants.js）为唯一判定源，
    // 不再由 DM settings 拉取结果决定——去 DM 权限耦合（无 DM 权限时总览按钮仍显示）。
    isControllable(d) {
      return d && isControllableUid(d.logicDeviceUniqueId)
    },
    // 跳设备控制页并锚点聚焦该设备（路由 name= integration-..._device_control，query.focus=uid）
    goDeviceControl(d) {
      this.drawerVisible = false
      this.$router.push({ name: 'integration-env-air-station-manager_device_control', query: { focus: d.logicDeviceUniqueId } })
    },
    // 瓦片核心参数行：按 catalog coreAttrs 顺序取 attrs 行；attr 缺数据时占位（不隐行，用户可感知缺参）
    coreParams(d) {
      const ids = catalogOf(d.logicDeviceUniqueId).coreAttrs
      return ids.map(id => d.attrs.find(a => a.attrId === id) || { attrId: id, displayName: id })
    },
    attrValue(a) {
      if (a.valueText != null && a.valueText !== '') return a.valueText
      return a.value == null ? '-' : a.value
    },
    offlineText(d) {
      if (d.offlineMs == null) return ''
      if (d.offlineMs < 3600000) return `${Math.max(1, Math.floor(d.offlineMs / 60000))}分`
      return `${(d.offlineMs / 3600000).toFixed(1)}小时`
    },
    relativeTime(updateTime) {
      if (!updateTime) return '-'
      const sec = Math.max(0, Math.floor((this.nowMs - new Date(updateTime).getTime()) / 1000))
      if (sec < 60) return `${sec}秒前`
      if (sec < 3600) return `${Math.floor(sec / 60)}分前`
      return `${(sec / 3600).toFixed(1)}小时前`
    },
    statusTier,
    statusColor,
  },
}
</script>

<style scoped>
.asm-page { padding: 12px; }
.asm-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.asm-title { font-size: 16px; font-weight: 600; }
.asm-hint { color: #909399; font-size: 12px; }
/* 单位切换容器：仅布局职责（右推 + 组内间距）；按钮/分段视觉已由 el-button / el-radio-button 接管 */
.asm-unit-switcher { display: inline-flex; align-items: center; gap: 4px; margin-left: auto; }
.asm-unit-label { font-size: 13px; color: #909399; margin-right: 4px; }
/* 状态筛选 chips：pill 徽章（element-plus 语义色）；激活态实心白字 */
.asm-chips { position: sticky; top: 0; z-index: 5; display: flex; gap: 8px; padding: 6px 0; margin-bottom: 8px; background: inherit; }
.asm-chip { font-size: 13px; padding: 2px 14px; border-radius: 14px; cursor: pointer; border: 1px solid #dcdfe6; background: #fff; color: #606266; }
.asm-chip.offline { border-color: #e6a23c; color: #e6a23c; }
.asm-chip.alarm { border-color: #f56c6c; color: #f56c6c; }
.asm-chip.active { background: #409eff; border-color: #409eff; color: #fff; }
.asm-chip.offline.active { background: #e6a23c; border-color: #e6a23c; color: #fff; }
.asm-chip.alarm.active { background: #f56c6c; border-color: #f56c6c; color: #fff; }
/* 报警徽标（瓦片头部 danger 三件套，ADM AdmStatusBadges danger 同款） */
.asm-groups { min-height: 120px; }
.asm-group { margin-bottom: 14px; }
.asm-group-head { display: flex; align-items: center; gap: 8px; padding: 4px 8px; background: #f5f7fa; border-radius: 4px; margin-bottom: 6px; }
.asm-group-name { font-weight: 600; color: #303133; font-size: 13px; }
.asm-group-count { font-size: 12px; color: #909399; }
.asm-tiles { display: flex; flex-wrap: wrap; gap: 8px; }
.asm-tile { width: 216px; border: 1px solid #ebeef5; border-radius: 6px; padding: 6px 10px; cursor: pointer; background: #fff; }
.asm-tile:hover { border-color: #409eff; }
.asm-tile-head { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.asm-tile-name { font-weight: 600; font-size: 13px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.asm-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.asm-dot.on { background: #67c23a; }
.asm-dot.off { background: #c0c4cc; }
.asm-dot.alarm { background: #f56c6c; }
/* 抽屉设备级状态条：报警徽章并集 + 离线信息（在线且无报警时不渲染） */
.asm-drawer-statusbar { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 8px; }
.asm-offline-text { color: #f56c6c; font-size: 12px; }
.asm-tile-param { display: flex; justify-content: space-between; font-size: 12px; line-height: 1.7; }
.asm-param-name { color: #606266; }
.asm-param-value { color: #303133; font-weight: 500; }
.asm-unit { color: #909399; margin-left: 3px; font-size: 11px; }
.asm-badge { font-size: 12px; padding: 1px 8px; border-radius: 10px; background: #f0f2f5; color: #606266; }
/* 抽屉状态徽章按枚举 key 档位配色（utils/statusBadge.js）：红=立即处置 / 橙=排查 / 绿=正常 / 灰=未知 */
.asm-badge.tier-danger { background: #fef2f2; color: #f56c6c; }
.asm-badge.tier-warning { background: #fdf6ec; color: #e6a23c; }
.asm-badge.tier-success { background: #f0f9eb; color: #67c23a; }
.asm-badge.tier-unknown { background: #f3f4f6; color: #909399; }
.asm-muted { color: #c0c4cc; }
.asm-empty { color: #909399; padding: 12px; text-align: center; }
.asm-drawer-meta { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; font-size: 13px; color: #303133; }
.asm-drawer-control-btn { margin-left: auto; }
.asm-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.asm-table th, .asm-table td { border-bottom: 1px solid #ebeef5; padding: 6px 8px; text-align: left; }
.asm-table th { background: #fafafa; color: #606266; }
/* 单位设置抽屉 */
.asm-unit-settings { padding: 0 4px; }
.asm-unit-settings-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.asm-unit-settings-actions { margin-top: 10px; }
.asm-unit-settings-hint { margin-top: 8px; font-size: 12px; color: #909399; line-height: 1.6; }
</style>
