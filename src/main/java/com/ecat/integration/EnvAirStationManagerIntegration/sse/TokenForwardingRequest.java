package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.Enumeration;

/**
 * 把 {@code ?token=xxx} query 注入 {@code Authorization} header 的请求包装器（D7 鉴权链复用）。
 *
 * <p><b>应用场景</b>：{@link AsmMonitorSseController#stream} SSE 端点收到的 token 在 URL query
 * （浏览器 EventSource 不支持自定义 header，详见 controller javadoc）。但 ruoyi {@code TokenService.getLoginUser}
 * 只从 {@code request.getHeader(header)} 读 token（header 名由 yml {@code token.header} 配，默认 {@code Authorization}），
 * 不会读 query。为<b>复用 ruoyi 全套 JWT+redis 鉴权链</b>（不绕过/不重写 private {@code parseToken}），
 * 用本包装器把 query token 伪装成 header——{@code TokenService} 从 header 读到 token 后正常走解析流程。
 *
 * <p><b>实现</b>：{@link HttpServletRequestWrapper} 覆写 {@code getHeader}/{@code getHeaders}/{@code getHeaderNames}
 * 三方法，对目标 header 名（如 {@code Authorization}）注入 token 值，其余 header 透传原 request。
 * <b>只读包装</b>，不修改原 request 的其他属性（attribute/parameter 等）。
 *
 * @author coffee
 */
class TokenForwardingRequest extends HttpServletRequestWrapper {

    /** 要注入的 header 名——取 ruoyi 默认 {@code Authorization}（yml {@code token.header} 默认值）。 */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final String injectedHeaderValue;

    /**
     * @param request           原生请求
     * @param injectedHeaderValue 注入到 {@code Authorization} header 的完整值（含 {@code Bearer } 前缀）
     */
    TokenForwardingRequest(HttpServletRequest request, String injectedHeaderValue) {
        super(request);
        this.injectedHeaderValue = injectedHeaderValue;
    }

    @Override
    public String getHeader(String name) {
        if (AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
            return injectedHeaderValue;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
            return Collections.enumeration(Collections.singletonList(injectedHeaderValue));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        // 原 header 名 + Authorization（若原请求无则补上，保证 TokenService 的 header 枚举能查到）
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Enumeration<String> original = super.getHeaderNames();
        while (original != null && original.hasMoreElements()) {
            names.add(original.nextElement());
        }
        names.add(AUTHORIZATION_HEADER);
        return Collections.enumeration(names);
    }
}
