// ASM 历史数据导出：前端循环分页拉全量扁平行集 + 本地生成 CSV 文件。
// 本集成 vue-modules 无 xlsx 依赖且禁新增 npm 依赖（ADM 有 xlsx 先例、ASM 不引）→
// 走 CSV：\uFEFF BOM 头（Excel 直接打开中文不乱码）+ 逗号分隔。若后续引入 xlsx，
// 只换本文件实现，页面调用点（fetchAllHistoryPages/downloadCsv）不变。

/** 导出循环分页单页行数（后端并行的 history 查询按 LIMIT/OFFSET 分页拉扁平行集）。 */
export const EXPORT_PAGE_SIZE = 1000

/** 导出行数上限（防超长窗口 × 多参数把浏览器内存打爆；截断时页面提示）。 */
export const EXPORT_MAX_ROWS = 50000

/**
 * 循环分页拉全量扁平行集：短页（rows < pageSize）即拉尽停止。
 * @param {(q: {pageNum: number, pageSize: number}) => Promise} fetchPage 页请求（queryHistory 的包装）
 * @param {{pageSize?: number, maxRows?: number}} [opts]
 * @returns {Promise<{rows: object[], truncated: boolean}>} rows=全窗行集（升序），truncated=触及上限被截断
 */
export async function fetchAllHistoryPages(fetchPage, { pageSize = EXPORT_PAGE_SIZE, maxRows = EXPORT_MAX_ROWS } = {}) {
  const all = []
  let pageNum = 1
  while (all.length < maxRows) {
    const res = await fetchPage({ pageNum, pageSize })
    const rows = (res && res.data && res.data.rows) || []
    all.push(...rows)
    if (rows.length < pageSize) break
    pageNum += 1
  }
  const truncated = all.length >= maxRows
  return { rows: truncated ? all.slice(0, maxRows) : all, truncated }
}

// CSV 字段转义：含逗号/引号/换行的字段用双引号包裹，内部引号翻倍（RFC 4180）
function csvEscape(v) {
  const s = v == null ? '' : String(v)
  return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

/**
 * 生成并下载 CSV 文件（BOM + 逗号分隔，\r\n 行尾——Excel/Numbers 双端打开正常）。
 * @param {string} filename 含 .csv 扩展名的完整文件名
 * @param {string[]} headerCells 表头单元格
 * @param {Array<Array<*>>} dataRows 数据行（调用方已排好序、已格式化；null/undefined → 空串）
 */
export function downloadCsv(filename, headerCells, dataRows) {
  const lines = [headerCells, ...dataRows].map((cells) => cells.map(csvEscape).join(','))
  const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
