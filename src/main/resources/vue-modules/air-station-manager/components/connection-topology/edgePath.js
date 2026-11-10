// 连线路径生成（纯函数，与 ADM connection-topology/edgePath.js 同构）：
// 参数槽 → 链路节点用三次贝塞尔，链路节点 → 设备用短直线。
// 拆成两段的原因：链路节点是真实的中间节点，线在它左沿收口、从右沿重新出发 ——
// 摘要文字永远是「节点内容」，不会被任何一条线压住。

/**
 * 槽 → 链路节点：水平出、水平入的三次贝塞尔（控制点取 dx 的一半，交叉角柔和）。
 * @param {number} x1 起点 x（槽卡右沿）
 * @param {number} y1 起点 y（槽行中心）
 * @param {number} x2 终点 x（链路节点左沿）
 * @param {number} y2 终点 y（链路节点中心）
 */
export function curvePath(x1, y1, x2, y2) {
  if (y1 === y2) return `M ${x1} ${y1} L ${x2} ${y2}`
  const dx = (x2 - x1) * 0.45
  return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
}

/** 链路节点 → 设备卡：节点与设备等高的短直线（同一 y）。 */
export function hopPath(x1, x2, y) {
  return `M ${x1} ${y} L ${x2} ${y}`
}

/**
 * 贝塞尔参数点（取 t 处的坐标）：给「沿线的状态标记」用，当前不需要，留给后续扩展。
 */
export function bezierPoint(x1, y1, x2, y2, t) {
  const dx = (x2 - x1) * 0.45
  const u = 1 - t
  return {
    x: u * u * u * x1 + 3 * u * u * t * (x1 + dx) + 3 * u * t * t * (x2 - dx) + t * t * t * x2,
    y: u * u * u * y1 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y2,
  }
}
