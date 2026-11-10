/**
 * 「其他配置」区块取数：entry.data 顶层里**不属于连接参数、也不属于设备身份卡**的标量字段。
 *
 * 移植自 ADM `config-display/shared/extraConfig.js`。背景：连接面板只渲染连接模板认识的键；
 * 序列号(sn)、采集间隔(poll_interval_sec) 这类部署参数散落在 entry.data 顶层，
 * 既不在连接配置里，也不该混进连接分项——单独成块展示。
 *
 * 过滤规则：
 *   ① 只取顶层标量（对象/数组/空值跳过，嵌套结构留给原始配置）；
 *   ② 跳过连接参数（按「共性连接类型」键集合判定，扁平落盘时也不会与连接分项重复）；
 *   ③ 跳过设备身份卡已展示的键（设备名/型号/厂商/集成坐标）与流程元数据；
 *   ④ 跳过配置流的「表单说明字段」——只读 TextConfigItem，作用是给人看提示语，
 *      不是设备配置（如 class_type_label「请选择设备类型。支持…」）。
 */
import { CONNECTION_KEYS, canonicalKey } from '../../comm/normalizeComm.js'
import { toFieldRows } from '../../comm/formatters.js'

/** 身份卡已展示 / 流程元数据：不进「其他配置」（sn、poll_interval_sec 等保留） */
const META_KEYS = new Set([
  'id', 'entryid', 'deviceid', 'uniqueid', 'unique_id',
  'coordinate', 'title', 'name', 'vendor', 'model', 'class',
  'flowtype', 'flow_type', 'createdat', 'created_at', 'updatedat', 'updated_at',
  // 配置流末步的「确认创建」勾选与预览 YAML，属流程动作而非设备配置
  'confirmed', 'configsummary',
])

/**
 * 表单说明字段的键名特征（`_` 前缀是流程内部中间态；`*_label`/`*_tip`/`*_hint` 等
 * 是只读提示语，各集成在生成配置摘要时也会主动 remove 掉它们）。
 */
const SCAFFOLD_KEY_RE = /^_|_(label|tip|hint|desc|description|note|help|remark|placeholder|msg)$/i

/** 展示优先级：身份性字段在前；未列出的按落盘顺序追加 */
const EXTRA_ORDER = ['sn', 'poll_interval_sec']

const norm = (k) => String(k || '').toLowerCase().replace(/[^a-z0-9]/g, '')

/** 疑似整句说明文字（配置值几乎不会含中文句号），用于兜住命名不规范的提示字段 */
function looksLikeHintText(val) {
  return typeof val === 'string' && val.length > 24 && /[。！]/.test(val)
}

/**
 * @param {object} data entry.data 原始对象
 * @returns {Array<{key: string, label: string, value: string}>} 可直接喂 FieldRows 的行
 */
export function extractExtraRows(data) {
  if (!data || typeof data !== 'object') return []
  /** 规范键 → 值（顺序即落盘顺序），让 labelOf 能命中中文释义 */
  const collected = new Map()
  for (const [key, val] of Object.entries(data)) {
    if (val == null || val === '') continue
    if (typeof val === 'object') continue
    if (SCAFFOLD_KEY_RE.test(key)) continue
    if (looksLikeHintText(val)) continue
    const canon = canonicalKey(key) || key
    if (CONNECTION_KEYS.has(canon)) continue
    if (META_KEYS.has(canon) || META_KEYS.has(norm(key))) continue
    if (!collected.has(canon)) collected.set(canon, val)
  }
  if (!collected.size) return []
  // 稳定排序：已知键按优先级，其余保持落盘顺序
  const ordered = [
    ...EXTRA_ORDER.filter((k) => collected.has(k)),
    ...[...collected.keys()].filter((k) => !EXTRA_ORDER.includes(k)),
  ]
  const shaped = {}
  for (const key of ordered) shaped[key] = collected.get(key)
  return toFieldRows(shaped, { keys: ordered })
}
