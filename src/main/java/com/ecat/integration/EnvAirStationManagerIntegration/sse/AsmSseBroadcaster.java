package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ASM SSE 广播器——维护在线 {@link SseEmitter} 池，供 {@code AsmSseConsumer} 单事件调广播 +
 * 5s 心跳（comment 帧）防代理断连。平移 ADM {@code AdmSseBroadcaster} 模式（workspace 第二例）。
 *
 * <p><b>具名帧硬要求</b>：{@link #broadcast} 必须 {@code .name(AsmSseEvent.TYPE)}（event: device.data.update
 * 帧头）——无帧名的纯 data 帧前端具名 listener 永不触发（SSE 协议规定），这是推送链路唯一阻断点。</p>
 *
 * <p><b>死连接清理</b>：send 抛任何异常（IOException 或 RuntimeException——浏览器断连后死连接
 * 实测抛非 IO/非 ISE 的 RuntimeException）→ 立即移出池，单连接失败不得中断全池遍历；controller 三回调
 * （onCompletion/onTimeout/onError）也调 unregister，双重保险。</p>
 *
 * <p><b>Bean 注册</b>：{@code @Service}——DynamicJarLoader 只扫 @RestController/@Service（ASM 动态 jar
 * 单例铁律，@Component 静默跳过）。</p>
 */
@Slf4j
@Service
public class AsmSseBroadcaster {

    /** 在线 SseEmitter 池：sessionId → emitter。 */
    private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** 注册一个在线 emitter（controller stream 新连接调）。 */
    public void register(String sessionId, SseEmitter emitter) {
        emitters.put(sessionId, emitter);
    }

    /** 注销 emitter（controller 三回调 + broadcast 死连接清理双调）。 */
    public void unregister(String sessionId) {
        emitters.remove(sessionId);
    }

    /** 在线连接数（consumer 据此短路——零连接跳序列化开销）。 */
    public int activeCount() {
        return emitters.size();
    }

    /** 连接是否在线（测试断言用）。 */
    public boolean isActive(String sessionId) {
        return emitters.containsKey(sessionId);
    }

    /**
     * 广播单事件 JSON 给所有在线 emitter（AsmSseConsumer 调）。
     *
     * @param json AsmSseEvent 序列化后的 JSON 串
     */
    public void broadcast(String json) {
        emitters.forEach((id, em) -> {
            try {
                em.send(SseEmitter.event()
                        .name(AsmSseEvent.TYPE)
                        .data(json, MediaType.APPLICATION_JSON));
            } catch (RuntimeException | IOException e) {
                emitters.remove(id);
                log.debug("[诊断调试] ASM SSE 广播连接 {} 已断开，移出池：{}", id, e.toString());
            }
        });
    }

    /** 心跳：给所有在线 emitter 发 comment 帧（: ping，不进 data 通道，仅保活防代理超时）。 */
    public void heartbeat() {
        emitters.forEach((id, em) -> {
            try {
                em.send(SseEmitter.event().comment("ping"));
            } catch (RuntimeException | IOException e) {
                emitters.remove(id);
                log.debug("[诊断调试] ASM SSE 心跳连接 {} 已断开，移出池：{}", id, e.toString());
            }
        });
    }
}
