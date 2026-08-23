<template>
  <!--
    设备控制页（路由 name=device_control）：DM 配置的 7 台可控设备 card 墙。
    数据流：DM GET /device/control/settings（控件形态）+ ASM snapshot?unit=custom（中文名/数值实时值）
    → SSE /asm-monitor/stream 增量（dirty 字段保护不覆盖）→ 确认时串行逐 attr POST /asm-monitor/control
    + SSE control.completed 帧驱动终态徽章（终态唯一来源，无轮询；设计 §2/§4.1）。
    断连防护（设计 §4.4）：SSE 断连中禁「确认」；重连成功一次性补偿（snapshot 重拉 + 在途项按 id 单查）。
    与 DM DevicePanel 的差异：不立即提交，per-card 确认/撤销批量模型。
    入口：总览页详情抽屉「设备控制」按钮（带 ?focus={uid} 锚点滚动+高亮）。
    UI 层用 element-plus（宿主全局注册，el-* 免 import）；el-row/el-col 响应式等宽布局（紧凑：≥1200 一行 3 卡）。
  -->
  <div class="asm-page" data-asm="device-control-page">
    <div class="asm-toolbar">
      <span class="asm-title">设备控制</span>
      <el-tag :type="sseConnected ? 'success' : 'info'" size="small" effect="light">
        {{ sseConnected ? 'SSE 实时推送中' : '等待实时连接' }}
      </el-tag>
      <el-button size="small" :loading="loading" data-asm="ctl-refresh" @click="load">手动刷新</el-button>
    </div>

    <el-alert v-if="sseFailed" type="error" :closable="false" show-icon title="连接断开，重连中…" class="asm-banner" />

    <div v-if="catalogError" class="asm-empty asm-ctl-empty" data-asm="ctl-catalog-error">
      暂无受控设备或无权限查看（{{ catalogError }}）
    </div>
    <div v-loading="loading" class="asmc-wall">
      <el-row :gutter="12">
        <el-col v-for="card in cards" :key="card.uid" :xs="24" :md="12" :lg="8">
          <el-card
            :id="anchorId(card.uid)"
            shadow="hover"
            class="asmc-card"
            :class="{ highlight: focusUid === card.uid, 'device-offline': !card.online }"
            :data-asm-uid="card.uid"
          >
            <template #header>
              <div class="asmc-card-head">
                <div class="asmc-card-title">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" v-html="iconOf(card.logoType)" />
                  <span>{{ card.name }}</span>
                </div>
                <el-tag :type="card.online ? 'success' : 'info'" size="small" :effect="card.online ? 'light' : 'plain'">
                  {{ card.online ? '在线' : '离线' }}
                </el-tag>
              </div>
            </template>

            <div v-for="cmd in card.commands" :key="cmd.attributeId" class="asmc-item" :data-asm-item="cmd.attributeId">
              <span class="asmc-item-label">{{ cmd.commandName }}</span>
              <component
                :is="rendererFor(cmd.displayType)"
                :cmd="cmd"
                :value="effectiveValue(card, cmd)"
                :pending-value="isPending(card, cmd.attributeId) ? effectiveValue(card, cmd) : null"
                :disabled="!card.online || submittingOf(card)"
                @change="v => onUserChange(card, cmd, v)"
              />
              <el-tag v-if="badgeOf(card, cmd.attributeId)" size="small" disable-transitions
                :class="['asmc-result', 'r-' + badgeOf(card, cmd.attributeId).state]"
                :type="badgeTagType(badgeOf(card, cmd.attributeId).state)"
                :title="badgeOf(card, cmd.attributeId).error || ''">
                {{ badgeText(badgeOf(card, cmd.attributeId)) }}
              </el-tag>
              <el-tag v-else-if="isPending(card, cmd.attributeId)" size="small" disable-transitions effect="plain"
                class="asmc-result r-pending-edit">已修改</el-tag>
            </div>

            <!-- 底部动作区：常驻高度，确认/撤销出现/消失不跳动；SSE 断连中禁确认（提交后收不到终态帧） -->
            <div class="asmc-card-actions" :class="{ empty: !pendingCountOf(card) }">
              <template v-if="pendingCountOf(card)">
                <el-tooltip :disabled="sseConnected" content="实时连接断开" placement="top">
                  <span>
                    <el-button type="primary" size="small" :loading="submittingOf(card)"
                      :disabled="!sseConnected || submittingOf(card)" data-asm="ctl-confirm"
                      @click="confirmCard(card)">确认</el-button>
                  </span>
                </el-tooltip>
                <el-button plain size="small" :disabled="submittingOf(card)" data-asm="ctl-undo"
                  @click="undoCard(card)">撤销</el-button>
              </template>
            </div>
          </el-card>
        </el-col>
      </el-row>
      <div v-if="!loading && !catalogError && !cards.length" class="asm-empty asm-ctl-empty">暂无受控设备或无权限查看</div>
    </div>
  </div>
</template>

<script>
// keep-alive 契约：组件 name 必须等于路由 name（device_control）。
import { getSnapshot, getControlById } from '@/api/asm'
import { fetchControlSettings, buildCards } from '../control/catalog'
import { createDirtyStore, markPending, isDirty, pendingCount, pendingItems, undoCard, beginSubmit, setBadge, finishSubmit, settledValue, clearSettled } from '../control/dirtyState'
import { submitSerial, attachControlFrames, detachControlFrames, inFlightRecords } from '../control/executor'
import { AsmMonitorSseClient } from '../sse/AsmMonitorSseClient'
import valueChange from '../control/renderers/valueChange'
import command from '../control/renderers/command'
import commandStateless from '../control/renderers/commandStateless'
import select from '../control/renderers/select'
import valueReadonly from '../control/renderers/valueReadonly'

const RENDERERS = {
  value_change: valueChange,
  command,
  command_stateless: commandStateless,
  select,
  value: valueReadonly,
}

// 设备图标（与 DM DevicePanel deviceIcons 同源子集：本页 7 台设备涉及的 6 类 + 兜底）
const DEVICE_ICONS = {
  'air-conditioner': `<path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/><line x1="12" y1="22" x2="12" y2="17"/>`,
  'light': `<path d="M9 18h6M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/>`,
  'fan': `<path d="M12 12c-2-3-6-4-6-8a6 6 0 0 1 12 0c0 4-4 5-6 8z"/><path d="M12 12c3-2 4-6 8-6a6 6 0 0 1 0 12c-4 0-5-4-8-6z"/><path d="M12 12c2 3 6 4 6 8a6 6 0 0 1-12 0c0-4 4-5 6-8z"/><path d="M12 12c-3 2-4 6-8 6a6 6 0 0 1 0-12c4 0 5 4 8 6z"/>`,
  'door': `<path d="M5 2h14a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z"/><path d="M9 22V12h6v10"/><circle cx="15" cy="7" r="1"/>`,
  'tube': `<path d="M6 3v18"/><path d="M18 3v18"/><path d="M6 3h12"/><path d="M6 21h12"/><path d="M10 7h4M10 11h4M10 15h4"/>`,
  'power': `<path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/>`,
  'device': `<rect x="4" y="3" width="16" height="18" rx="2"/><line x1="8" y1="7" x2="16" y2="7"/><line x1="8" y1="11" x2="14" y2="11"/>`,
}

export default {
  name: 'device_control',
  data() {
    return {
      cards: [],
      loading: false,
      catalogError: '',
      dirty: createDirtyStore(),
      sseClient: null,
      sseConnected: false,
      sseFailed: false,
      focusUid: '',
    }
  },
  mounted() {
    this.load()
    this.startSse()
    // 入口锚点：?focus={uid} 滚动到 card + 短暂高亮（等 card 渲染后执行）
    this.focusUid = (this.$route && this.$route.query && this.$route.query.focus) || ''
    if (this.focusUid) this.scrollToFocus()
  },
  activated() {
    if (!this.sseClient) this.startSse()
  },
  deactivated() { this.stopSse() },
  beforeUnmount() { this.stopSse() },
  methods: {
    rendererFor(displayType) { return RENDERERS[displayType] || valueReadonly },
    iconOf(logoType) { return DEVICE_ICONS[logoType] || DEVICE_ICONS.device },
    anchorId(uid) { return 'asmc-card-' + uid.replace(/[^\w-]/g, '-') },
    submittingOf(card) { return !!this.dirty.submitting[card.uid] },
    pendingCountOf(card) { return pendingCount(this.dirty, card.uid) },
    isPending(card, attrId) { return !!(this.dirty.pending[card.uid] && this.dirty.pending[card.uid][attrId] != null) },
    badgeOf(card, attrId) {
      const b = this.dirty.badge[card.uid]
      return (b && b[attrId]) || null
    },
    badgeTagType(state) {
      return { SUCCESS: 'success', FAILED: 'danger', TIMEOUT: 'warning', PENDING: 'info' }[state] || 'info'
    },
    badgeText(badge) {
      if (badge.state === 'SUCCESS') return '成功'
      if (badge.state === 'FAILED') return '失败' + (badge.error ? '：' + badge.error : '')
      if (badge.state === 'TIMEOUT') return '超时'
      return '执行中…'
    },
    // 显示值三级优先（settled 收敛模型，设计 §settled）：pending（用户改动，SSE 不覆盖）
    // → settled（SUCCESS 后钉住提交值，等物理轮询回报→SSE 帧收敛；防 SUCCESS→旧值回跳）
    // → cmd.value（live 最新值，SSE 增量已合并）
    effectiveValue(card, cmd) {
      const p = this.dirty.pending[card.uid]
      if (p && p[cmd.attributeId] != null) return p[cmd.attributeId]
      const s = settledValue(this.dirty, card.uid, cmd.attributeId)
      if (s != null) return s
      return cmd.value
    },
    async load() {
      this.loading = true
      this.catalogError = ''
      try {
        const [dmRows, snapRes] = await Promise.all([
          fetchControlSettings(true),
          getSnapshot('custom').catch(() => null),   // snapshot 失败不阻塞（card 回退 DM title/value）
        ])
        const snapDevices = (snapRes && snapRes.data) || []
        this.__lastDmRows = dmRows
        this.cards = buildCards(dmRows, snapDevices)
        this.$nextTick(() => { if (this.focusUid) this.scrollToFocus() })
      } catch (e) {
        // DM 拉取失败（403/404/网络）：显式空态文案，不静默空白
        this.cards = []
        this.catalogError = (e && e.message) || '配置拉取失败'
      } finally {
        this.loading = false
      }
    },
    scrollToFocus() {
      const el = document.getElementById(this.anchorId(this.focusUid))
      if (!el) return
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      setTimeout(() => { if (this.focusUid) this.focusUid = '' }, 2500)  // 高亮 2.5s 后消隐
    },
    onUserChange(card, cmd, value) {
      markPending(this.dirty, card.uid, cmd.attributeId, value)
    },
    async confirmCard(card) {
      const items = pendingItems(this.dirty, card.uid).map(it => ({ uid: card.uid, attrId: it.attrId, value: it.value }))
      if (!items.length) return
      beginSubmit(this.dirty, card.uid, items)
      await submitSerial(items, {
        onTerminal: (item, outcome) => setBadge(this.dirty, card.uid, item.attrId, outcome.state, outcome.error),
      })
      finishSubmit(this.dirty, card.uid)
      const badges = this.dirty.badge[card.uid] || {}
      const failed = Object.values(badges).some(b => b.state !== 'SUCCESS')
      if (this.$message) {
        failed ? this.$message.warning('部分控制项未成功，详见行内徽章') : this.$message.success('控制已全部下发成功')
      }
    },
    undoCard(card) {
      undoCard(this.dirty, card.uid)
    },
    // 终态权威 afterValue 归一化为 cmd.value 同形态（终态帧/SSE 重连补偿单查两路共用）：
    // command/select 类 afterValue 是 displayValue 串（即 option label），按 cmd.options label 反查 key，
    //   反查不到回退提交值（返回 null；严格模式不猜 key）；value_change/value 类去单位尾
    //   （「27.0 °C」→「27.0」，按空格切首段）转数值串，非数值返回 null。
    normalizeAuthoritative(uid, attrId, afterValue) {
      if (afterValue == null) return null
      const card = this.cards.find(c => c.uid === uid)
      const cmd = card && card.commands.find(c => c.attributeId === attrId)
      if (!cmd) return null
      if (cmd.displayType === 'command' || cmd.displayType === 'select') {
        const opt = (cmd.options || []).find(o => o.label === afterValue || o.value === afterValue)
        return opt ? opt.value : null
      }
      const first = String(afterValue).split(' ')[0]
      return isNaN(Number(first)) ? null : first
    },
    startSse() {
      this.sseClient = new AsmMonitorSseClient({
        onUpdate: payload => this.handleSseUpdate(payload),
        onControlCompleted: frame => { if (this.__controlFrameEntry) this.__controlFrameEntry(frame) },
        onOpen: () => {
          const wasDown = this.sseFailed
          this.sseConnected = true
          this.sseFailed = false
          // 重连成功一次性补偿（设计 §4.4）：重拉 snapshot + 在途（PENDING 未终态）项按 id 单查对齐
          // （GET /control/{id} 为生命周期事件单查，非轮询）
          if (wasDown) this.compensateAfterReconnect()
        },
        onError: () => { this.sseFailed = true },
      })
      this.__controlFrameEntry = attachControlFrames(frame => {
        if (typeof window !== 'undefined') window.__asmCtlDoneFrames = (window.__asmCtlDoneFrames || 0) + 1
        if (frame && frame.uid && frame.attrId && frame.result) {
          setBadge(this.dirty, frame.uid, frame.attrId,
            frame.result === 'FAILED' ? 'FAILED' : frame.result,
            frame.result === 'FAILED' ? (frame.error || '执行失败') : undefined,
            this.normalizeAuthoritative(frame.uid, frame.attrId, frame.afterValue))
        }
      })
      this.sseClient.start()
    },
    stopSse() {
      if (this.sseClient) { this.sseClient.stop(); this.sseClient = null }
      detachControlFrames()
      this.__controlFrameEntry = null
      this.sseConnected = false
    },
    // 断连丢帧补偿：snapshot 重拉 + 对在途项（badge 仍 PENDING）按 id 单查对齐终态（一次性，非轮询）
    async compensateAfterReconnect() {
      getSnapshot('custom').then(snapRes => {
        const snapDevices = (snapRes && snapRes.data) || []
        if (snapDevices.length && this.__lastDmRows) this.cards = buildCards(this.__lastDmRows, snapDevices)
      }).catch(() => {})   // snapshot 失败不阻塞补偿（在途单查独立进行）
      for (const it of inFlightRecords()) {
        const b = this.dirty.badge[it.uid] && this.dirty.badge[it.uid][it.attrId]
        if (!b || b.state !== 'PENDING') continue
        try {
          const res = await getControlById(it.id)
          const rec = res && res.data
          if (rec && rec.result && rec.result !== 'PENDING') {
            setBadge(this.dirty, it.uid, it.attrId,
              rec.result === 'FAILED' ? 'FAILED' : rec.result,
              rec.result === 'FAILED' ? (rec.error || '执行失败') : undefined,
              this.normalizeAuthoritative(it.uid, it.attrId, rec.afterValue))
          }
        } catch (e) { /* 单查失败保留 PENDING（executor 20s 兜底会落 TIMEOUT） */ }
      }
    },
    // SSE 增量：帧覆盖 card 项 live 值；dirty（pending/提交中）字段丢弃帧值保用户值。
    // 枚举类（command/select）帧只带中文 label（valueText），经 option label→key 反查；反查不到保持现值
    //（严格模式：不猜 key）。数值类走 displayValue（本页 custom 口径）+ unit；只读走 valueText。
    handleSseUpdate(payload) {
      if (typeof window !== 'undefined') window.__asmCtlFrames = (window.__asmCtlFrames || 0) + 1
      const card = this.cards.find(c => c.uid === payload.logicDeviceUniqueId)
      if (!card) return
      card.online = true
      const cmd = card.commands.find(c => c.attributeId === payload.attrId)
      if (!cmd) return
      if (isDirty(this.dirty, card.uid, cmd.attributeId)) return   // dirty 保护：帧不覆盖用户改动
      // settled 收敛（设计 §settled）：SUCCESS 后物理下个轮询回报前，帧携带的是旧值
      // （实测：SUCCESS 后 150ms 内即有旧值帧到达）——收敛窗内信任提交值：
      //   帧值==settled.value → 收敛成功，清 settled 此后跟随 live；
      //   帧值!=settled.value → 判为迟到旧帧，忽略；deadline 过（settledValue 惰性清）回跟随 live。
      //   其他渠道改写的真相最多延迟 SETTLED_MAX_MS 可见（有上限可接受）。
      const settled = settledValue(this.dirty, card.uid, cmd.attributeId)
      let next = null, unit = null
      if (cmd.displayType === 'value_change' && payload.displayValue != null) {
        next = String(payload.displayValue)
        unit = payload.unit || null
      } else if (cmd.displayType === 'command' || cmd.displayType === 'select') {
        const opt = (cmd.options || []).find(o =>
          (payload.valueText != null && o.label === payload.valueText) ||
          (payload.displayValue != null && String(payload.displayValue) === o.value))
        if (opt) next = opt.value
      } else if (cmd.displayType === 'value') {
        next = payload.valueText != null ? payload.valueText
          : (payload.displayValue != null ? String(payload.displayValue) : null)
        unit = payload.unit || null
      }
      if (next == null) return   // 枚举反查不到保持现值（严格模式：不猜 key）
      if (settled != null && next !== settled) return   // 收敛窗内迟到旧帧不覆盖
      if (settled != null) clearSettled(this.dirty, card.uid, cmd.attributeId)   // 帧值==提交值：收敛
      cmd.value = next
      if (unit) cmd.liveUnit = unit
    },
  },
}
</script>

<style scoped>
.asm-page { padding: 12px; }
.asm-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.asm-title { font-size: 16px; font-weight: 600; }
.asm-banner { margin-bottom: 10px; }
.asm-empty { color: #909399; padding: 12px; text-align: center; }

/* card 墙：el-row gutter=12 + el-col xs/md/lg 响应式等宽（≥1200 一行 3 卡）；col 内 el-card 满宽等高 */
.asmc-wall { min-height: 120px; }
.asmc-wall :deep(.el-col) { margin-bottom: 12px; }
/* 紧凑布局定案（设计 §3.3）：卡 body padding 12、行距收紧、label 列 72px 定宽 */
.asmc-card { height: 100%; }
/* important：宿主 ruoyi 全局主题对 .el-card__body 有更高优先级竞争；本选择器以 .asmc-card 命名空间
   隔离不泄漏到宿主页面，压宿主主题取紧凑 12px（设计 §3.3）。 */
.asmc-card :deep(.el-card__body) { padding: 12px !important; }
.asmc-card.device-offline :deep(.el-card__body) { opacity: .65; }
.asmc-card.highlight { outline: 2px solid #409eff; outline-offset: 2px; box-shadow: 0 0 12px rgba(64, 158, 255, .45) !important; }
.asmc-card-head { display: flex; align-items: center; justify-content: space-between; }
.asmc-card-title { display: flex; align-items: center; gap: 6px; font-weight: 600; font-size: 14px; color: #303133; }
.asmc-card-title svg { width: 18px; height: 18px; color: #409eff; }

/* 控件项行：label 定宽 + 控件右对齐统一，项间距统一（紧凑：gap 8 / padding 5 / label 72px） */
.asmc-item { display: flex; align-items: center; gap: 8px; padding: 5px 0; border-bottom: 1px dashed #f0f2f5; }
.asmc-item:last-of-type { border-bottom: none; }
.asmc-item-label { min-width: 72px; flex-shrink: 0; color: #606266; font-size: 13px; }

/* value_change / 只读 */
.asmc-vc { display: inline-flex; align-items: center; gap: 6px; }
.asmc-unit { color: #909399; font-size: 12px; }
.asmc-readonly { font-size: 14px; font-weight: 500; color: #303133; }

/* 开关标签 / 按钮组 */
.asmc-toggle-wrap { display: inline-flex; align-items: center; gap: 8px; }
.asmc-toggle-label { font-size: 13px; color: #303133; }
.asmc-btn-group { display: inline-flex; gap: 8px; flex-wrap: wrap; }

/* 提交徽章 / 卡片动作（常驻高度防跳动） */
.asm-ctl-empty { width: 100%; }
.asmc-card-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 10px; padding-top: 10px; border-top: 1px solid #f0f2f5; min-height: 42px; }
.asmc-card-actions.empty { border-top-style: dashed; }
</style>
