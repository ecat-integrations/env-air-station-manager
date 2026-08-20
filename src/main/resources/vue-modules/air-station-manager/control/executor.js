// 控制提交执行器（流式终态，无轮询——设计 §2/§4.1 定案）：
// 确认后串行逐 attr 提交（后端单线程 executor，设计要求串行勿改并发），
// 每项 POST /asm-monitor/control 拿 AsmControlRecord id → 等 SSE control.completed 具名帧
// （后端 finalizeOutcome 落终态即广播）按 id 匹配落终态。单项失败不阻断后续（逐项回调终态）。
//
// 帧等待模型：页面在 SSE onmessage(control.completed) 时回调 attachControlFrames 注入的 handler，
// executor 内部维护 pending map {id→resolve}；POST 返回 id 后 await 对应 promise。
// 20s 兜底超时（后端 10s 超时必落帧，20s 仅是丢帧保险——超时按 TIMEOUT 显示）。
// SSE 断连丢帧的补偿不走这里：页面重连成功后按 id 调 GET /asm-monitor/control/{id} 单查（生命周期事件非轮询）。

import { executeControl } from '@/api/asm'

/** 帧兜底等待上限：后端 10s 超时必落 control.completed 帧，20s 覆盖帧丢失场景。 */
const FRAME_WAIT_MS = 20000

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'TIMEOUT'])

/** 在途帧等待注册表：record id → resolve(outcome)。模块级单例（页面与 SSE 接线共享）。 */
const frameWaiters = new Map()

/** SSE 帧入口（attachControlFrames 注入；null=未接线，帧直接丢弃走 20s 兜底）。 */
let frameEntry = null

/**
 * 接线 SSE control.completed 帧——返回一个帧处理函数，页面把它作为 SSE onmessage
 * (control.completed) 回调传入：内部先唤醒在途等待者（executor pending map 按 id 匹配落终态），
 * 再透传给调用方 handler（页面侧扩展用）。
 * @param {Function} [handler] (frame:{id,uid,attrId,result,error}) => void
 * @returns {Function} 帧处理函数（喂给 SSE 客户端 onControlCompleted）
 */
export function attachControlFrames(handler) {
  frameEntry = frame => {
    const resolve = frameWaiters.get(frame && frame.id)
    if (resolve) {
      frameWaiters.delete(frame.id)
      resolve(frameOutcome(frame))
    }
    if (typeof handler === 'function') handler(frame)
  }
  return frameEntry
}

/** 注销（页面卸载调，防泄漏：SSE 帧不再进 executor 等待表，在途项走 20s 兜底）。 */
export function detachControlFrames() {
  frameEntry = null
}

/** 帧结果 → 徽章 outcome（与既有徽章语义一致：FAILED 带 error 串）。 */
function frameOutcome(frame) {
  return frame && frame.result === 'FAILED'
    ? { state: 'FAILED', error: frame.error || '执行失败' }
    : { state: frame ? frame.result : 'TIMEOUT' }
}

/** 在途记录登记表：id → {uid, attrId}（SSE 重连补偿单查用；终态即摘）。 */
const inFlight = new Map()

/** 当前在途控制项快照 [{id, uid, attrId}]（页面重连成功后按 id 单查对齐徽章）。 */
export function inFlightRecords() {
  return Array.from(inFlight.entries()).map(([id, it]) => ({ id, ...it }))
}

/** 等待某 record id 的终态帧；窗口内未到 → TIMEOUT（丢帧保险）。 */
function awaitFrame(id) {
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      frameWaiters.delete(id)
      resolve({ state: 'TIMEOUT' })
    }, FRAME_WAIT_MS)
    frameWaiters.set(id, outcome => {
      clearTimeout(timer)
      resolve(outcome)
    })
  })
}

/**
 * 串行提交一组控制项。
 * @param {Array<{uid, attrId, value}>} items 同一卡的待提交项（按用户改动顺序）
 * @param {Object} hooks { onStart(item), onTerminal(item, {state, error?}) }
 *   onStart 在每项 POST 前调（页面置 PENDING 徽章）；onTerminal 每项终态各调一次，
 *   state ∈ SUCCESS / FAILED / TIMEOUT；FAILED 带 error 串（行内徽章展示）。
 */
export async function submitSerial(items, { onStart, onTerminal } = {}) {
  for (const item of items) {
    if (onStart) onStart(item)
    let outcome
    try {
      const res = await executeControl({ uid: item.uid, attrId: item.attrId, value: String(item.value) })
      const rec = res && res.data
      if (rec && TERMINAL.has(rec.result)) {
        // 同步终态（罕见：执行极快先于返回）——直接落，不注册帧等待
        outcome = rec.result === 'FAILED' ? { state: 'FAILED', error: rec.error || '执行失败' } : { state: rec.result }
      } else if (rec && rec.id != null) {
        inFlight.set(rec.id, { uid: item.uid, attrId: item.attrId })
        try {
          outcome = await awaitFrame(rec.id)
        } finally {
          inFlight.delete(rec.id)
        }
      } else {
        outcome = { state: 'FAILED', error: '提交未返回记录 id' }
      }
    } catch (e) {
      outcome = { state: 'FAILED', error: (e && (e.message || e.msg)) || '提交失败' }
    }
    if (onTerminal) onTerminal(item, outcome)
  }
}
