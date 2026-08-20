// ASM 站房管理 API（宿主 axios 经 utils/request Proxy 注入）。
//
// 端点契约逐一对应 ASM controller：
//   AsmSnapshotController     GET  /asm-monitor/snapshot
//   AsmHistoryQueryController GET  /asm-monitor/history
//   AsmAlarmRuleController    /asm-monitor/alarm-rule CRUD（list 返回裸数组，非 AjaxResult）
//   AsmAlarmRecordController  GET  /asm-monitor/alarm-record/list
//   AsmControlController      POST /asm-monitor/control

import request from '@/utils/request'

/**
 * 站房设备当前态快照（GET /asm-monitor/snapshot，对齐 ADM unit gate）。
 * @param {string} [unit] 显示单位模式：'standard'（默认，STANDARD 行标准口径）/ 'custom'（MONITOR 偏好换算）
 * @returns {Promise} res.data = [{logicDeviceUniqueId, attrs:[{attrId,value,valueText,unit,updateTime,source}]}]
 */
export function getSnapshot(unit) {
  return request({ url: '/asm-monitor/snapshot', method: 'get', params: unit ? { unit } : undefined })
}

/**
 * 历史数据查询（GET /asm-monitor/history）。
 * @param {object} q { granularity:'MINUTE'|'FIVE_MIN'|'HOUR', start, end,
 *   params:'uid:attrId,...', mode:'BACK'|'FRONT', unit:'standard'|'custom', pageNum, pageSize }
 *   start/end = ISO-8601 LocalDateTime 壁钟串（datetime-local 原样提交，后端按 core JVM 壁钟转 UTC）。
 * @returns {Promise} res.data = { granularity, mode, unit, pageNum, pageSize,
 *   rows:[{dataTime, logicDeviceUniqueId, attrId, value, unit, validCount, totalCount}] }
 */
export function queryHistory(q) {
  return request({
    url: '/asm-monitor/history',
    method: 'get',
    params: {
      granularity: q.granularity,
      start: q.start,
      end: q.end,
      params: q.params || '',
      mode: q.mode,
      unit: q.unit,
      pageNum: q.pageNum,
      pageSize: q.pageSize,
    },
  })
}

/** 报警规则列表（GET /asm-monitor/alarm-rule/list）——controller 返回裸 JSON 数组（非 AjaxResult）。 */
export async function listAlarmRules() {
  const res = await request({ url: '/asm-monitor/alarm-rule/list', method: 'get' })
  return Array.isArray(res) ? res : (res && res.data) || []
}

/** 单条规则（GET /asm-monitor/alarm-rule/{id}）。 */
export function getAlarmRule(id) {
  return request({ url: `/asm-monitor/alarm-rule/${id}`, method: 'get' })
}

/** 新增规则（POST /asm-monitor/alarm-rule，后端 parse 校验 + 热加载）。 */
export function addAlarmRule(rule) {
  return request({ url: '/asm-monitor/alarm-rule', method: 'post', data: rule })
}

/** 修改规则（PUT /asm-monitor/alarm-rule）。 */
export function editAlarmRule(rule) {
  return request({ url: '/asm-monitor/alarm-rule', method: 'put', data: rule })
}

/** 删除规则（DELETE /asm-monitor/alarm-rule/{id}）。 */
export function removeAlarmRule(id) {
  return request({ url: `/asm-monitor/alarm-rule/${id}`, method: 'delete' })
}

/**
 * 报警记录分页（GET /asm-monitor/alarm-record/list）。
 * @param {object} q { start, end, uid?, status?:'ACTIVE'|'INACTIVE', pageNum, pageSize }
 * @returns {Promise} res.data = { total, rows:[AsmAlarmRecord] }
 */
export function listAlarmRecords(q) {
  return request({
    url: '/asm-monitor/alarm-record/list',
    method: 'get',
    params: {
      start: q.start,
      end: q.end,
      uid: q.uid || undefined,
      status: q.status || undefined,
      pageNum: q.pageNum,
      pageSize: q.pageSize,
    },
  })
}

/**
 * REMOTE 控制（POST /asm-monitor/control，caller=认证 principal）。
 * @param {object} body { uid, attrId, value }
 * @returns {Promise} res.data = AsmControlRecord（含 id/result/beforeValue/afterValue/durationMs）
 */
export function executeControl(body) {
  return request({ url: '/asm-monitor/control', method: 'post', data: body })
}

/**
 * 按主键单查控制记录（GET /asm-monitor/control/{id}）。
 * 仅设计用途=SSE 重连补偿单查（重连成功后对在途 PENDING 项一次性按 id 对齐终态，
 * 生命周期事件触发非轮询通道，设计 §4.4）。
 * @param {number|string} id asm_control_record 主键
 * @returns {Promise} res.data = AsmControlRecord
 */
export function getControlById(id) {
  return request({ url: '/asm-monitor/control/' + id, method: 'get' })
}

/**
 * 控制记录分页（GET /asm-monitor/control-record/list）。
 * @param {object} q { start, end, uid?, origin?:'REMOTE'|'LOCAL', result?:'PENDING'|'SUCCESS'|'FAILED'|'TIMEOUT', pageNum, pageSize }
 * @returns {Promise} res.data = { total, rows:[AsmControlRecord] }
 */
export function listControlRecords(q) {
  return request({
    url: '/asm-monitor/control-record/list',
    method: 'get',
    params: {
      start: q.start,
      end: q.end,
      uid: q.uid || undefined,
      origin: q.origin || undefined,
      result: q.result || undefined,
      pageNum: q.pageNum,
      pageSize: q.pageSize,
    },
  })
}

/**
 * 可查 stat 参数元数据（GET /asm-monitor/stat-params）——与 Java SDK listStatParams 同源
 * （asm_config_stat JOIN asm_config_unit(STORAGE) 投影），历史页参数候选池数据源。
 * @returns {Promise} res.data = [{logicDeviceUniqueId, attrId, paramDisplayName, storageUnit, applicableGranularityMask}]
 */
export function listStatParams() {
  return request({ url: '/asm-monitor/stat-params', method: 'get' })
}

/** 聚合配置行（GET /asm-monitor/config-stat）。res.data = [AsmConfigStat] */
export function getConfigStat() {
  return request({ url: '/asm-monitor/config-stat', method: 'get' })
}

/**
 * 改单 series 聚合配置（PUT /asm-monitor/config-stat）。响应 msg 注明「配置不回溯历史」。
 * @param {object} body { logicDeviceUniqueId, attrId, enabled, granularityMask, materializationMode }
 */
export function putConfigStat(body) {
  return request({ url: '/asm-monitor/config-stat', method: 'put', data: body })
}

/** 单位偏好行 MONITOR/HISTORY（GET /asm-monitor/config-unit）。res.data = [AsmConfigUnit] */
export function getConfigUnit() {
  return request({ url: '/asm-monitor/config-unit', method: 'get' })
}

/**
 * 写单条单位偏好（PUT /asm-monitor/config-unit，后端写后失效单位缓存即时生效）。
 * @param {object} body { logicDeviceUniqueId, attrId, purpose:'MONITOR'|'HISTORY', unit, displayPrecision }
 *   displayPrecision 0-6 可空；空=本次不改小数位（后端 upsert coalesce 不覆盖）
 */
export function putConfigUnit(body) {
  return request({ url: '/asm-monitor/config-unit', method: 'put', data: body })
}
