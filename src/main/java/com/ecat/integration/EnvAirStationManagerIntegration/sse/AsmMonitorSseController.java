package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ASM 站房监控 SSE 端点（{@code GET /asm-monitor/stream?token=xxx}）——总览页瓦片墙实时增量推送，
 * 平移 ADM {@code AdmMonitorSseController} 模式（?token= query 自解 + 5s 心跳 + 具名帧 device.data.update）。
 *
 * <p><b>鉴权（双通道）</b>：前端用 @microsoft/fetch-event-source（fetch-based 可带 Authorization header 过
 * ruoyi 安全过滤器）；token 同时保留 ?token= query，本端点经 {@link TokenForwardingRequest} 把 query token
 * 注入 Authorization header 后复用 ruoyi {@link TokenService} 全套 JWT+redis 链路自解（不依赖 private
 * parseToken）。token 缺失/无效 → 401（严格模式不臆测匿名放行）。</p>
 *
 * <p><b>生命周期</b>：SseEmitter onCompletion/onTimeout/onError 三回调统一 unregister + broadcast/heartbeat
 * 死连接清理，双重防泄漏。心跳 5s daemon ScheduledExecutor（@PostConstruct 起 / @PreDestroy 停，无 sleep）。</p>
 *
 * @author coffee
 */
@Slf4j
@RestController
@RequestMapping("/asm-monitor")
public class AsmMonitorSseController {

    /** 心跳间隔（5s）。代理（nginx）默认 60s 空闲超时，5s 留足余量且不刷屏。 */
    static final long HEARTBEAT_INTERVAL_SECONDS = 5L;

    /** SSE 无限超时（长连接，由心跳 + 客户端关决定生命周期）。 */
    private static final long SSE_TIMEOUT_NEVER = 0L;

    private final AsmSseBroadcaster broadcaster;
    private final TokenService tokenService;

    /** 心跳调度器（daemon 单线程，容器生命周期绑定）。 */
    private ScheduledExecutorService heartbeatExecutor;

    @Autowired
    public AsmMonitorSseController(AsmSseBroadcaster broadcaster, TokenService tokenService) {
        this.broadcaster = broadcaster;
        this.tokenService = tokenService;
    }

    /**
     * SSE 流端点（@Anonymous 进 permitAll + ?token= query 手动校验双层）。
     *
     * @param token   query token（必填）
     * @param request 原生请求（用于复用 ruoyi 鉴权链）
     * @return SseEmitter（已注册到 broadcaster）
     * @throws UnauthorizedException token 缺失/无效 → 转 401
     */
    @Anonymous
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token,
                             HttpServletRequest request) {
        LoginUser loginUser = resolveLoginUser(token, request);
        if (loginUser == null) {
            throw new UnauthorizedException("ASM SSE 未鉴权或 token 无效");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_NEVER);
        String sessionId = UUID.randomUUID().toString();
        emitter.onCompletion(() -> broadcaster.unregister(sessionId));
        emitter.onTimeout(() -> broadcaster.unregister(sessionId));
        emitter.onError(e -> broadcaster.unregister(sessionId));
        broadcaster.register(sessionId, emitter);
        log.debug("[诊断调试] ASM SSE 新连接 sessionId={} user={} activeCount={}",
                sessionId, loginUser.getUsername(), broadcaster.activeCount());
        return emitter;
    }

    /** 鉴权解析：query token 经 TokenForwardingRequest 注入 header 后复用 ruoyi TokenService。 */
    private LoginUser resolveLoginUser(String queryToken, HttpServletRequest request) {
        if (StringUtils.isEmpty(queryToken)) {
            return null;
        }
        String headerValue = queryToken.startsWith("Bearer ") ? queryToken : "Bearer " + queryToken;
        TokenForwardingRequest forwarded = new TokenForwardingRequest(request, headerValue);
        return tokenService.getLoginUser(forwarded);
    }

    /** 启动心跳（@PostConstruct；daemon 线程 JVM 退出不挂起）。 */
    @PostConstruct
    public void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "asm-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(
                this::heartbeatTick,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        log.info("[诊断调试] ASM SSE 心跳已启动，间隔 {}s", HEARTBEAT_INTERVAL_SECONDS);
    }

    /**
     * 心跳 task 体（scheduleAtFixedRate 入口）：必 catch——task 抛未捕获异常会被调度器
     * 永久抑制（心跳死亡，全池连接随后被代理断掉）。吞 + error 日志，保调度连续
     * （与 AsmStatRefreshScheduler.scheduledTick 同纪律）。
     */
    void heartbeatTick() {
        try {
            broadcaster.heartbeat();
        } catch (RuntimeException e) {
            log.error("[诊断调试] ASM SSE 心跳 tick 异常（已吞，保调度连续）", e);
        }
    }

    /** 关闭心跳（@PreDestroy）。 */
    @PreDestroy
    public void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
            log.info("[诊断调试] ASM SSE 心跳已停止");
        }
    }

    /** SSE 未鉴权异常（controller 抛出，由全局处理转 401）。 */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
