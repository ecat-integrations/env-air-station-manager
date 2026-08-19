/**
 * ADM 前端时区策略唯一出口。
 *
 * 后端统一以 ISO 8601 UTC（带 Z，如 '2026-08-12T05:00:00Z'）与前端交换；
 * 前端所有时间展示（历史桶 / 列表时间列 / 弹框）与 datetime-local 查询输入框填充，
 * 都经本文件转用户壁钟——禁止在页面里再散写 new Date().getHours() 或 String(iso).replace('T',' ')。
 *
 * 时区基 seam 在 localParts()：当前按浏览器本地时区（new Date 的本地壁钟方法）。
 * 若业务要固定时区（如运维永远看 Asia/Shanghai），只改 localParts() 一个函数——
 * 换成 toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}) 拆字段，或手动 +8 偏移，
 * 全部展示与输入调用点自动跟进，无需各页散改。
 */

// 时区基：ISO UTC 串 / Date → 本地壁钟字段 [年, 月, 日, 时, 分, 秒]。固定时区策略只改这里。
function localParts(input) {
  const d = input instanceof Date ? input : new Date(input)
  return [d.getFullYear(), d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds()]
}

const pad = (n) => String(n).padStart(2, '0')

/**
 * 后端 ISO UTC → 本地壁钟展示串。
 * @param {string|Date} input 后端 ISO 8601 UTC 串或 Date 实例
 * @param {boolean} [minute=false] true=截到分钟 'YYYY-MM-DD HH:mm'；默认秒级 'YYYY-MM-DD HH:mm:ss'
 * @returns {string} 本地壁钟展示串；空入参返 '-'
 */
export function formatLocalDateTime(input, minute = false) {
  if (!input) return '-'
  const [Y, M, D, h, m, s] = localParts(input)
  const hm = `${pad(h)}:${pad(m)}`
  return minute ? `${Y}-${pad(M)}-${pad(D)} ${hm}` : `${Y}-${pad(M)}-${pad(D)} ${hm}:${pad(s)}`
}

/**
 * Date / ISO UTC → datetime-local 输入框填充串（本地壁钟 'YYYY-MM-DDTHH:mm:ss'，秒精度）。
 * 供查询条件 <input type="datetime-local" step="1"> 填充；与展示串共用 localParts()，
 * 固定时区改一处即展示与输入同步。
 * @returns {string} 本地壁钟 'T' 分隔串；空入参返 ''
 */
export function formatLocalInputSeconds(input) {
  if (!input) return ''
  const [Y, M, D, h, m, s] = localParts(input)
  return `${Y}-${pad(M)}-${pad(D)}T${pad(h)}:${pad(m)}:${pad(s)}`
}
