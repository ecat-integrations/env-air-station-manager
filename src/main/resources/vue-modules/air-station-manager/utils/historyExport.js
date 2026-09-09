// ASM 历史数据导出：全窗按网格切段拉取 + 本地生成 CSV 文件。
// 本集成 vue-modules 无 xlsx 依赖且禁新增 npm 依赖（ADM 有 xlsx 先例、ASM 不引）→
// 走 CSV：\uFEFF BOM 头（Excel 直接打开中文不乱码）+ 逗号分隔。若后续引入 xlsx，
// 只换本文件实现，页面调用点（downloadCsv）不变。
// （切段循环在页面 exportCsv 内：段=页网格切分的同一模型，这里只承载常量与文件落盘。）

/** 导出循环单段覆盖的网格 tick 数（段边界=网格边界，与页面浏览分页同模型、段大小独立）。 */
export const EXPORT_PAGE_TICKS = 1000

/** 导出行数上限（防超长窗口 × 多参数把浏览器内存打爆；截断时页面提示）。 */
export const EXPORT_MAX_ROWS = 50000

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
