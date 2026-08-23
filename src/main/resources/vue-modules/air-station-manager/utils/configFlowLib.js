/**
 * 加载 ASM 自带的 config-flow lit lib（注册 <flow-form> 自定义元素）——从
 * env-air-device-manager 同机制移植，publicPath 换本集成。
 *
 * lib 物理源：vue-modules/static/lib/config-flow/dist/（经 webpack CopyPlugin copy 进
 * dist/lib/config-flow/）。bundle 由 ruoyi IntegrationLoader 以 {VITE_APP_BASE_API} + module.url
 * 注入，本函数从本集成 bundle 的 <script> tag 反推 base+publicPath 拼 lib 子路径，
 * dev(/dev_api)/prod/staging 三环境自动适配，无需硬编码前缀。
 *
 * 幂等：customElements 已注册 flow-form 或本次加载已发起，直接复用，不重复注入 script。
 */

let loadingPromise = null;

export function loadConfigFlowLib() {
  // 幂等：已注册则立即完成；进行中则复用同一 Promise，不重复注入 script
  if (typeof window !== "undefined" && window.customElements && window.customElements.get("flow-form")) {
    return Promise.resolve();
  }
  if (loadingPromise) return loadingPromise;

  loadingPromise = new Promise((resolve) => {
    const s = document.createElement("script");
    s.type = "module";
    s.src = resolveLibUrl();
    s.onload = () => resolve();
    // 不 reject：lib 加载失败时让 <flow-form> 不渲染（上层 v-if 控制），可见的空状态比异常崩溃更易排查
    s.onerror = () => {
      console.error("[asm] config-flow lib 加载失败，src=", s.src);
      loadingPromise = null; // 允许失败后重试
      resolve();
    };
    document.head.appendChild(s);
  });
  return loadingPromise;
}

/**
 * 从本集成 bundle 的 script tag 反推 lib 完整 URL。
 * bundle src 形如 {base}/ecat-integrations/integration-env-air-station-manager/air-station-manager.js，
 * lib 同 publicPath 下 lib/config-flow/dist/ecat-config-flow.esm.js。
 */
function resolveLibUrl() {
  const tag = document.querySelector('script[src*="/ecat-integrations/integration-env-air-station-manager/"]');
  if (tag && tag.src) {
    return tag.src.replace(/air-station-manager\.js.*$/, "lib/config-flow/dist/ecat-config-flow.esm.js");
  }
  // 兜底：本集成 bundle 未正常经 IntegrationLoader 加载（环境异常），用无前缀直连路径降级。
  return "/ecat-integrations/integration-env-air-station-manager/lib/config-flow/dist/ecat-config-flow.esm.js";
}
