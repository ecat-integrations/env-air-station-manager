import request from '@/utils/request'

/**
 * 启动装载门控（语义复刻 ADM waitAdmReady 的 stat-config.initialized 信号）：airstation
 * logic 设备由 INTEGRATIONS_ALL_LOADED 事件集中创建，起动窗口内 registry 空——直接渲染会把
 * 37 槽显示成「全部未配置」误导。放行键是**后端完成点信号**而非数据形态/超时：
 * 轮询 `/asm-monitor/device/ready` 的 initialized（airstation createAllStationDevices
 * 尾部置位，零设备也置位；集成禁用恒 true）——零设备环境照样放行显示真实空态，
 * 未就绪则持续骨架（无时间上限，由后端信号决定）。
 */
export async function waitAsmReady(pollMs = 3000) {
  for (;;) {
    try {
      const res = await request({ url: '/asm-monitor/device/ready', method: 'get' })
      if (res && res.data && res.data.initialized) return
    } catch (e) {
      // core 起动中端点不可达——与 initialized=false 同等对待（继续轮询，不打断）
      console.warn('[asm] 等待初始化期间 ready 不可达，继续轮询', e && e.message)
    }
    await new Promise(r => setTimeout(r, pollMs))
  }
}

// ASM 站房设备配置 API（设计 §REST 11 端点，StationDeviceController 返回 AjaxResult 包裹；
// request 拦截器按 code===200 放行，data 在 res.data）。
// type = 22 类型 37 槽的槽键（StationParamMeta.getType()）：单实例=类型名（TH），
//   多实例=类型.实例（AIR_CONDITIONER.ac1 / CAMERA.1 / VALVE_GROUP.so2 ...），点号在 path 段合法。

// 37 类型槽及绑定状态（三态 CONFIGURED/UNBOUND/NOT_CREATED，纯读 registry）
export function listParamBindings() {
  return request({ url: '/asm-monitor/device/params', method: 'get' })
}

// 某类型槽可选厂家/型号（profile 注册表过滤，矩阵外无）
export function listVendors(type) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/vendors`, method: 'get' })
}

// provision（driver 快进身份步 + 停连接步）→ {flowId, status, stoppedStepId, schema, stepInputs}
// data: {coordinate, model, sn, name, operation:'add'|'replace', oldDeviceId?}
export function provision(type, data) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/provision`, method: 'post', data })
}

// 推进 config flow step（CREATE_ENTRY 那次后端原子收口）→ {type:'SHOW_FORM'|'CREATE_ENTRY', stepId, schema?, entryId?}
export function submitStep(flowId, data) {
  return request({ url: `/asm-monitor/device/flow/${flowId}/submit`, method: 'post', data })
}

// 上一步
export function previousStep(flowId) {
  return request({ url: `/asm-monitor/device/flow/${flowId}/previous`, method: 'post' })
}

// 移除类型槽绑定
export function unbindType(type) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/unbind`, method: 'post' })
}

// 详情：类型槽绑定设备连接信息（entry.data 原样 dump）
export function getDeviceDetail(type) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/device`, method: 'get' })
}

// 改连接：对已绑设备启动 RECONFIGURE flow → {flowId, stepId, schema, stepInputs}
export function reconfigure(type) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/reconfigure`, method: 'post' })
}

// 复用：列该类型槽兼容的已存在物理设备
export function listCompatibleDevices(type) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/devices`, method: 'get' })
}

// 复用直绑。data: {physicalDeviceId, oldDeviceId?}
export function bindExisting(type, data) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/bind`, method: 'post', data })
}

// 更换（= provision operation=replace）
export function replaceDevice(type, data) {
  return request({ url: `/asm-monitor/device/params/${encodeURIComponent(type)}/replace`, method: 'post', data })
}
