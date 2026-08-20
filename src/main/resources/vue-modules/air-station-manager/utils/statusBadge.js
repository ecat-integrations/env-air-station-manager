// ASM 状态徽章颜色映射（P3）：AttributeStatus 枚举名（后端 status 字段）→ 颜色档。
//
// 映射抄自 ADM air-device-manager constants/badge-type.js（STATUS_BADGE_TYPE），口径：
//   danger 红 = 立即处置（超限/失明/仪器失效）；warning 琥珀 = 排查（离线/数据态不佳/统计异常）；
//   success 绿 = 正常；info/未知/null = 灰（上下文质控标记与未知态不抢眼）。
// 严禁按中文 statusName 串匹配配色（文案随 i18n 漂移即失效）——只认枚举 key。
export const ASM_STATUS_TIER = Object.freeze({
  // danger 红
  OVER_UPPER_LIMIT: 'danger',
  UNDER_LOWER_LIMIT: 'danger',
  ALARM: 'danger',
  MALFUNCTION: 'danger',
  DEVICE_REPLACEMENT: 'danger',
  // warning 琥珀（OFFLINE 归本档，数据无效类橙显）
  NO_CHANGE: 'warning',
  ABNORMAL_CHANGE: 'warning',
  OFFLINE: 'warning',
  INSUFFICIENT: 'warning',
  WAITING: 'warning',
  // success 绿
  NORMAL: 'success',
})

// 档 → 色值（value 列文字色同源）：danger #f56c6c / warning #e6a23c / success #67c23a / 未知灰 #909399
export const TIER_COLOR = Object.freeze({
  danger: '#f56c6c',
  warning: '#e6a23c',
  success: '#67c23a',
  unknown: '#909399',
})

/** 枚举 key → 颜色档；null/未知枚举一律 unknown 灰（不吞、不猜）。 */
export function statusTier(statusKey) {
  return ASM_STATUS_TIER[statusKey] || 'unknown'
}

/** 枚举 key → 文字/徽章前景色（抽屉状态徽章 + 值列文字共用）。 */
export function statusColor(statusKey) {
  return TIER_COLOR[statusTier(statusKey)]
}
