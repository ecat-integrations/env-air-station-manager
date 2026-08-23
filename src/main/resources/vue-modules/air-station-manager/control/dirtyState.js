// per-card dirty 状态机：用户改动（待确认项）+ 提交徽章 + SSE 合并的 dirty 保护。
//
// 模型（设计定稿，非 DM 的立即提交）：
//   pending[uid][attrId] = 待提交值（用户已改未确认；command_stateless 点击同样记入）
//   badge[uid][attrId]   = 提交徽章 {state:'PENDING'|'SUCCESS'|'FAILED'|'TIMEOUT', error?}（行内展示）
//   submitting[uid]      = 确认串行提交进行中（期间禁再改）
//   settled[uid][attrId] = { value, deadline } 提交成功后的收敛保持（见 SETTLED_MAX_MS）
// SSE 合并规则：帧到达时该 attr 若 dirty（有 pending 或 submitting 中）则丢弃帧值保用户值，否则实时跟随。
// 撤销 = 清 pending（显示回落 live 最新值）；徽章保留到下一次用户改动或新确认（提交审计可视化）。

import { reactive } from 'vue'

/**
 * settled 兜底窗上限（3s）。
 * 已实现「帧到即收敛」：control.completed 终态帧带权威 afterValue（SUCCESS 时 core attr 可读状态
 * 必为新值——数值型乐观更新 / Command 型 ACK 后同步更新），SUCCESS 落 settled 即用权威值，无回跳。
 * 本窗仅兜防迟到旧值数据帧——设备周期 poll 与写传播竞态会短暂回报旧值（实测 SUCCESS 后
 * 150ms 内即有旧值帧到达），3s 覆盖一个轮询周期足够；过期后恢复跟随 live。
 */
export const SETTLED_MAX_MS = 3000

/** 建 dirty store（页面级单例，reactive 驱动 Vue 渲染）。 */
export function createDirtyStore() {
  return reactive({
    /** uid -> { [attrId]: 待提交值(String) } */
    pending: {},
    /** uid -> { [attrId]: {state, error?} } */
    badge: {},
    /** uid -> bool */
    submitting: {},
    /** uid -> { [attrId]: {value: 提交值, deadline: 毫秒时间戳} } */
    settled: {},
    /** uid -> { [attrId]: 最近一批提交值 }（setBadge 落 SUCCESS 时据此记 settled——帧驱动终态到达时 pending 可能已清） */
    lastSubmitted: {},
  })
}

const cardMap = (store, key, uid) => {
  if (!store[key][uid]) store[key][uid] = {}
  return store[key][uid]
}

/** 记用户改动（不提交）。同 attr 再改覆盖。 */
export function markPending(store, uid, attrId, value) {
  if (store.submitting[uid]) return
  cardMap(store, 'pending', uid)[attrId] = String(value)
  // 新改动清该 attr 旧徽章 + 旧 settled（用户已不认可上轮结果/收敛值）
  const b = store.badge[uid]
  if (b) delete b[attrId]
  clearSettled(store, uid, attrId)
}

/** 该 attr 当前是否 dirty（pending 有值或整卡提交中）。 */
export function isDirty(store, uid, attrId) {
  return !!(store.pending[uid] && store.pending[uid][attrId] != null) || !!store.submitting[uid]
}

/** 整卡 dirty 数（确认/撤销按钮显隐）。 */
export function pendingCount(store, uid) {
  return Object.keys(store.pending[uid] || {}).length
}

/** 撤销：清整卡 pending + settled（显示回落 live 最新值；徽章不清——未提交的改动无徽章，残留旧徽章保留展示）。 */
export function undoCard(store, uid) {
  delete store.pending[uid]
  delete store.settled[uid]
}

/** 取该卡全部待提交项 [{attrId, value}]（快照，供 executor 串行消费）。 */
export function pendingItems(store, uid) {
  return Object.entries(store.pending[uid] || {}).map(([attrId, value]) => ({ attrId, value }))
}

/** 确认开始：清整卡旧徽章/旧 settled（新一轮反馈开始，上轮 SUCCESS/FAILED 退场）+ 锁卡 +
 *  记本批提交值（SUCCESS 终态记 settled 的值源）+ 逐项置 PENDING 徽章。 */
export function beginSubmit(store, uid, items) {
  store.submitting[uid] = true
  delete store.badge[uid]
  delete store.settled[uid]
  const submitted = cardMap(store, 'lastSubmitted', uid)
  const badges = cardMap(store, 'badge', uid)
  for (const it of items) {
    submitted[it.attrId] = it.value
    badges[it.attrId] = { state: 'PENDING' }
  }
}

/** 单项终态徽章（SUCCESS / FAILED(error 必填) / TIMEOUT）。
 *  SUCCESS 且该 attr 本批次提交过 → 记 settled：优先用 authoritativeValue（终态帧/单查携带的
 *  权威 afterValue，已归一化为 cmd.value 同形态），解析失败回退 lastSubmitted（见 SETTLED_MAX_MS）；
 *  FAILED/TIMEOUT 不记（回落 live）。 */
export function setBadge(store, uid, attrId, state, error, authoritativeValue) {
  cardMap(store, 'badge', uid)[attrId] = state === 'FAILED' ? { state, error: error || '执行失败' } : { state }
  if (state === 'SUCCESS') {
    const v = authoritativeValue != null ? String(authoritativeValue)
      : (store.lastSubmitted[uid] && store.lastSubmitted[uid][attrId])
    if (v != null) cardMap(store, 'settled', uid)[attrId] = { value: String(v), deadline: Date.now() + SETTLED_MAX_MS }
  }
}

/** 清某 attr 的 settled（SSE 帧值==提交值收敛成功 / 用户改动 / 撤销 / 新批次时调；过期由 settledValue 惰性清）。 */
export function clearSettled(store, uid, attrId) {
  const s = store.settled[uid]
  if (s) delete s[attrId]
}

/** 取未过期的 settled 值（惰性过期：过期即清并返回 null，供显示优先级 pending→settled→live）。
 *  渲染路径调用（非 computed 上下文），清过期项是安全的响应式写。 */
export function settledValue(store, uid, attrId) {
  const s = store.settled[uid] && store.settled[uid][attrId]
  if (!s) return null
  if (Date.now() > s.deadline) {
    clearSettled(store, uid, attrId)
    return null
  }
  return s.value
}

/** 确认结束：解锁 + 清 pending（成功项 settled 钉住提交值直到 SSE 帧收敛；失败项回落 live，避免脏值滞留显示）。 */
export function finishSubmit(store, uid) {
  store.submitting[uid] = false
  delete store.pending[uid]
}
