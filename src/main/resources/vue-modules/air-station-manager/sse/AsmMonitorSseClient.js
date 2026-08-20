// ASM 站房监控 SSE 客户端：长连接消费 /asm-monitor/stream，收到 device.data.update 事件回调上层刷新参数卡片。
// 断线指数退避重连。
//
// 架构约束（plan §0.2）：ASM UI 不调 /core-api/*,故不复用 ecat-core-api 的 EventApi（端点不同）,
// 但重连模式（指数退避 1s→2s→4s→…→30s 上限、连通归零）与 core-api event-api.js 同源。
//
// 鉴权（bug-record-20260807-185700）：用 @microsoft/fetch-event-source 的 fetch-based SSE（非浏览器原生
// EventSource）——根因是原生 EventSource 不能发自定义 header（W3C 规范局限），而 ruoyi 鉴权只读
// Authorization header，故 SSE 请求无 header 被安全过滤器回 401（@Anonymous 对动态 jar 不生效）。
// fetch-based SSE 能带 Authorization header → JwtAuthenticationTokenFilter 读 header 鉴权放行 → 进 controller。
// token 同时保留在 ?token= query（controller resolveLoginUser 读 query 自解，后端零改动）；header 过滤器、
// query 控制器，二者同 JWT 无冲突。token 默认从 Cookie Admin-Token 读（bundle 运行时无 @/utils/auth
// webpack alias，故直接解析 document.cookie 零依赖）。构造时支持注入 tokenProvider 便于单测替身。

// fetch-based EventSource 替代（@microsoft/fetch-event-source）：支持自定义 header（根治 401）、
// AbortController 干净终止、Promise 化控制流。webpack 打进 dist（非 externals，自包含）。
import { fetchEventSource, EventStreamContentType } from '@microsoft/fetch-event-source'

// fetch SSE 不经 axios,须手动镜像 host axios 的 baseURL（dev=/dev-api、prod=/prod-api）才能走 vite/prod 代理；
// 裸 /asm-monitor/stream 在 vite dev 不被代理→404。request 是 .index.js install 时 request.init(utils.request)
// 注入的 host axios 代理,request.get() 取底层 axios 实例读其 defaults.baseURL。
import request from '@/utils/request'

/** SSE 端点（Spring MVC SseEmitter，后端 @Anonymous + ?token= 自解，见 AsmMonitorSseController）。 */
const STREAM_URL = '/asm-monitor/stream'

/** SSE 事件帧名（后端 broadcast .name("device.data.update") 具名帧，onmessage 按 evt.event 字段匹配帧头）。 */
const EVENT_DEVICE_DATA_UPDATE = 'device.data.update'

/** 退避基数 1s，上限 30s（与 core-api event-api.js 同档，平衡重连压力与恢复速度）。 */
const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 30000

/**
 * 从 Cookie Admin-Token 读 ruoyi 登录 token（默认 tokenProvider）。
 *
 * bundle 运行时挂在 ruoyi-ui-v3 (Vite) 下,Cookie 由 ruoyi 登录写入;不依赖 js-cookie（package.json
 * 无此依赖,不加）,直接 document.cookie 解析。Cookie 无 HttpOnly 时 JS 可读——Admin-Token 是
 * Authorization header 用的 access token,ruoyi-ui-v3 src/utils/auth.js 用 js-cookie Cookies.get 读取,
 * 非 HttpOnly,本函数同等可读。
 *
 * @returns {string|null} 裸 token（无 Bearer 前缀，后端 controller 自动补）
 */
function readTokenFromCookie() {
  if (typeof document === 'undefined' || !document.cookie) return null
  // Cookie 形如 "key1=v1; key2=v2; Admin-Token=xxx; ..."——split+find,不用正则避免 = 编码歧义
  for (const part of document.cookie.split(';')) {
    const eq = part.indexOf('=')
    if (eq < 0) continue
    const k = part.slice(0, eq).trim()
    if (k === 'Admin-Token') {
      return part.slice(eq + 1).trim()
    }
  }
  return null
}

/**
 * 读 host ruoyi axios 的 baseURL（dev=/dev-api、prod=/prod-api）。fetch SSE 不经 axios 须手动拼前缀走代理,
 * 否则 vite dev 下裸 /asm-monitor/stream 不被 proxy 代理→404（与 snapshot 走 request 同 baseURL 同源）。
 * request.get() 取 .index.js 注入的底层 axios 实例;未 init（单测场景）get() 抛→返 '' 裸路径（单测不连真端点）。
 * @returns {string} baseURL 前缀（如 '/dev-api'）或 '' （不可用时退裸路径）
 */
function defaultBaseUrlProvider() {
  try {
    const axios = request.get()
    return (axios && axios.defaults && axios.defaults.baseURL) || ''
  } catch (e) {
    return ''
  }
}

/**
 * ASM 站房监控 SSE 客户端（fetch-based，@microsoft/fetch-event-source）。
 *
 * 生命周期：
 *   const client = new AsmMonitorSseClient({ onUpdate: envelope => ... })
 *   client.start()        // 卡片页 mounted
 *   ...
 *   client.stop()         // 卡片页 unmounted（防泄漏，清退避 timer + abort fetch）
 *
 * 回调约定（调用方注入，默认 no-op）：
 *   - onUpdate(envelope)：收到 device.data.update 事件，envelope = {id, type, timestamp, payload:{...}}
 *   - onOpen()：连接建立（重连成功也算，退避计数归零）
 *   - onError(error)：连接异常触发（已自动安排重连，回调仅用于 UI 提示）
 *
 * 重连策略：全自管指数退避（不用 lib 内置重试）——onerror/onclose 触发后 throw/resolve 终止 lib，
 * 交 scheduleReconnect 走自己的 1s→30s 退避，与 core-api event-api.js 同源。stop() 经 AbortController
 * 干净终止（lib 对 abort 直接 resolve，不触发 onerror/onclose，故不会安排重连）。
 */
export class AsmMonitorSseClient {
  /**
   * @param {Object} [opts]
   * @param {Function} [opts.onUpdate]    收到 device.data.update 回调，envelope 入参
   * @param {Function} [opts.onOpen]      连接建立回调
   * @param {Function} [opts.onError]     onerror 回调（已自动重连，此处仅 UI 提示）
   * @param {Function} [opts.tokenProvider]    token 取值函数（默认 readTokenFromCookie，单测注入替身）
   * @param {Function} [opts.baseUrlProvider]  baseURL 取值函数（默认 defaultBaseUrlProvider，单测注入替身）
   */
  constructor({ onUpdate, onOpen, onError, tokenProvider, baseUrlProvider } = {}) {
    this.onUpdate = onUpdate || (() => {})
    this.onOpen = onOpen || (() => {})
    this.onError = onError || (() => {})
    this.tokenProvider = tokenProvider || readTokenFromCookie
    this.baseUrlProvider = baseUrlProvider || defaultBaseUrlProvider

    this.abortController = null
    this.reconnectAttempts = 0
    this.reconnectTimer = null
    /** stop() 后不再重连（组件卸载防泄漏）。 */
    this.stopped = false
  }

  /** 启动客户端（清 stopped 标记 + 首次连接）。重复 start 幂等：已有进行中的连接则 no-op。 */
  start() {
    this.stopped = false
    if (this.abortController) return
    this.connect()
  }

  /** 建立 fetch-based SSE 连接（内部方法，start/scheduleReconnect 调）。 */
  connect() {
    if (this.stopped) return

    // 清残留退避 timer：start() 直调或外部复用 connect() 场景 timer 可能残留，主动 clear 防止
    // "连接已建但旧 timer 还在"状态错乱（对齐 core-api event-api.js disconnect 清 timer 模式）。
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }

    const token = this.tokenProvider()
    if (!token) {
      // 严格模式：无 token 不臆测匿名连接（后端必 401），明确抛异常让上层处理（登录过期/未登录）
      throw new Error('[AsmMonitorSseClient] token 缺失——ruoyi 会话未登录或 Cookie Admin-Token 丢失,无法建立 SSE 连接')
    }

    // 清残留控制器（重连场景旧控制器已废但引用还在），abort 是无害 no-op；随后建新控制器承载本次连接。
    if (this.abortController) {
      this.abortController.abort()
    }
    this.abortController = new AbortController()

    // token 经 query 传（controller resolveLoginUser 读 query 自解，后端零改动）；baseUrl 前缀镜像 host axios
    // （dev=/dev-api）走 vite/prod 代理。Authorization header 见下方 fetchEventSource 配置（过安全过滤器）。
    const baseUrl = this.baseUrlProvider()
    const url = `${baseUrl}${STREAM_URL}?token=${encodeURIComponent(token)}`

    // fetch-based SSE：fetchEventSource 返回 Promise（流正常结束 resolve、onerror throw reject、abort resolve）。
    // 不 await（fire-and-forget），用 .catch 吞 onerror/onopen throw 透传的 reject（重连已在 onerror 内安排）。
    fetchEventSource(url, {
      method: 'GET',
      // 关键：Authorization header 让 ruoyi JwtAuthenticationTokenFilter 鉴权放行（根治原生 EventSource 不能带
      // header 致 401，bug-record-20260807-185700）。Accept 头示意 SSE（Spring SseEmitter 始终回 text/event-stream）。
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: EventStreamContentType
      },
      signal: this.abortController.signal,
      onopen: async (response) => {
        // 非 text/event-stream（如安全过滤器 401 回 application/json、或网关错页）→ 抛出，lib 转 onerror 走重连。
        // response.ok 兜 HTTP 错误态（4xx/5xx）；content-type 核对防"伪 200 但非 SSE 流"。
        const ct = response.headers.get('content-type') || ''
        if (!response.ok || !ct.includes(EventStreamContentType)) {
          throw new Error(`ASM SSE 端点返回非 ${EventStreamContentType}: HTTP ${response.status} (${ct})`)
        }
        this.reconnectAttempts = 0
        this.onOpen()
      },
      onmessage: (evt) => {
        // fetch-event-source 的 onmessage 收所有帧（具名 + 无名），按 evt.event 字段过滤 device.data.update
        // （后端 broadcast .name("device.data.update") 具名帧；EventSourceMessage.event 存帧名）。
        // 不在此裁剪/校验 envelope 结构——上层（卡片/规则引擎）按需读字段，客户端只做透明转发。
        if (evt.event !== EVENT_DEVICE_DATA_UPDATE) return
        try {
          this.onUpdate(JSON.parse(evt.data))
        } catch (e) {
          // JSON 解析失败：后端发的是非 JSON 或 JSON 畸形，记录原始数据便于排查（不抛——单帧坏不应杀整个连接）
          console.error('[诊断调试] ASM SSE device.data.update 事件解析失败', e, evt && evt.data)
        }
      },
      onerror: (err) => {
        // fetch 失败 / onopen throw / 流中断都汇聚到此（lib 把 onopen throw 也转交 onerror）。
        this.onError(err)
        if (this.stopped) throw err  // stop() 后抛出终止 lib（防 abort 后又重连）
        // 抛出终止 lib 内置退避（lib 默认重试不可控），改用自管 scheduleReconnect（1s→30s 与 core-api 同源）。
        this.scheduleReconnect()
        throw err
      },
      onclose: () => {
        // 流正常结束（服务端关连接 / SseEmitter 超时）——lib 此后 resolve 不自动重连，须自管重连。
        if (this.stopped) return
        this.scheduleReconnect()
      }
    }).catch((e) => {
      // onerror/onopen throw 透传至此（reject）；onclose 正常结束是 resolve 不入此分支；abort 也是 resolve。
      // 重连已在 onerror 内经 scheduleReconnect 安排，此处仅吞掉避免未处理 promise rejection（真实错误已透 onError）。
      if (!this.stopped) {
        console.warn('[诊断调试] ASM SSE 连接终止，将按退避重连', e && e.message)
      }
    })
  }

  /** 指数退避安排重连（1s → 2s → 4s → … → 上限 30s;连通后 reconnectAttempts 归零）。 */
  scheduleReconnect() {
    if (this.stopped) return
    if (this.reconnectTimer) return  // 已在退避等待，不重复安排
    const delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, this.reconnectAttempts), RECONNECT_MAX_MS)
    this.reconnectAttempts++
    console.warn(`[诊断调试] ASM SSE 断连,${delay}ms 后重连（第 ${this.reconnectAttempts} 次）`)
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  /** 停止客户端（组件卸载调）：清退避 timer + abort fetch，设 stopped 防止 scheduleReconnect 安排新重连。 */
  stop() {
    this.stopped = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.abortController) {
      // abort：lib 直接 resolve（不触发 onerror/onclose），故不会安排重连；stopped 标记双保险。
      this.abortController.abort()
      this.abortController = null
    }
  }
}
