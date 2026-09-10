package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PreDestroy;

/**
 * ASM SSE 广播器——维护在线 {@link SseEmitter} 池，供 {@code AsmSseConsumer} 单事件调广播 +
 * 5s 心跳（comment 帧）防代理断连。平移 ADM {@code AdmSseBroadcaster} 模式（workspace 第二例）。
 *
 * <p><b>具名帧硬要求</b>：{@link #broadcast} 必须 {@code .name(AsmSseEvent.TYPE)}（event: device.data.update
 * 帧头）——无帧名的纯 data 帧前端具名 listener 永不触发（SSE 协议规定），这是推送链路唯一阻断点。</p>
 *
 * <p><b>死连接清理（异常路径）</b>：send 抛任何异常（IOException 或 RuntimeException——浏览器断连后死连接
 * 实测抛非 IO/非 ISE 的 RuntimeException）→ 立即移出池，单连接失败不得中断全池遍历；controller 三回调
 * （onCompletion/onTimeout/onError）也调 unregister，双重保险。</p>
 *
 * <p><b>单条 send 有界超时（bug-record-20260826-174500）</b>：异常路径只救「send 会抛」的死连接；
 * 对端停读的连接 TCP 接收窗归零 → Tomcat 阻塞 flush（NioEndpoint doWrite）<b>永不返回也永不抛</b>——
 * 若 send 内联在广播线程上，一条这样的连接就把单线程广播域整体钉死（全部订阅者断流、新连接拿不到响应头）。
 * 故每条 send 投递到 {@code asm-sse-send} helper 线程执行，广播线程只做有界等待
 * （{@link Future#get(long, TimeUnit)}，默认 {@link #SEND_TIMEOUT_MILLIS}）：超时即判死连接摘除 + 中断
 * helper（cancel(true)——阻塞写停在可中断的 monitor wait 上）+ 继续服务下一连接。广播域单线程 FIFO 语义
 * 不变（等待仍在广播线程，后续广播仍排队）。helper 池用 cached：同一时刻只有一个 send 在飞（广播循环串行
 * 等待），池实际只需 1 线程 + 超时摘除后尚未退出的挂起 helper，不增长。</p>
 *
 * <p><b>广播异步化（bug-record-20260826-005500）</b>：send 循环全部在专用单线程 daemon executor
 * {@code asm-sse-broadcast} 上执行（SSE send 属 IO 型轻操作，独立执行域——与 controller 心跳调度
 * {@code asm-sse-heartbeat} 同模式声明线程身份）；broadcast/broadcastNamed/heartbeat 对调用方投递即返。
 * 调用方包含总线 lane 线程（modbus-source worker 等）——同步 send 曾被死连接的 servlet 写阻塞 10s+
 * 钉死 lane，后续写事务连环 12s 硬超时。单 worker FIFO 保序 → 订阅方帧时序与同步实现一致。</p>
 *
 * <p><b>Bean 注册</b>：{@code @Service}——DynamicJarLoader 只扫 @RestController/@Service（ASM 动态 jar
 * 单例铁律，@Component 静默跳过）。</p>
 *
 * @author coffee
 */
@Slf4j
@Service
public class AsmSseBroadcaster {

    /** 在线 SseEmitter 池：sessionId → emitter。 */
    private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** 广播执行域（send 循环的载体；测试注入同步 executor 手动驱动）。 */
    private final Executor broadcastExecutor;

    /**
     * 单条 send 的 helper 执行域：每条帧的实际 send 在此线程上执行，广播线程只做有界等待
     * （见类 javadoc「单条 send 有界超时」）。cached 池——同一时刻仅一个 send 在飞（广播循环
     * 串行等待），常态 1 线程；超时摘除后挂起的旧 helper 线程等中断/容器写超时自行退出。
     */
    private final ExecutorService sendExecutor;

    /** 单条 send 的等待上界（毫秒）。 */
    private final long sendTimeoutMillis;

    /**
     * 单条 send 的默认硬超时：健康连接的帧发送是毫秒级 LAN/浏览器写，5s 有千倍余量；
     * 取心跳间隔同值（5s）——最坏单条卡顿 = 一个心跳周期，广播域停顿界与既有节奏常量一致；
     * 一个 5s 内排不出单帧的连接也必然跟不上 5s 心跳节奏，摘除由前端 fetch-event-source 重连兜底。
     */
    static final long SEND_TIMEOUT_MILLIS = 5000L;

    /** 生产构造：自建单线程广播 executor + 默认 send 超时（见类 javadoc「广播异步化」）。 */
    public AsmSseBroadcaster() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "asm-sse-broadcast");
            t.setDaemon(true);
            return t;
        }), SEND_TIMEOUT_MILLIS);
    }

    /** 测试构造：注入 executor（同步 {@code Runnable::run} 或真实单线程），send 超时取默认。 */
    AsmSseBroadcaster(Executor broadcastExecutor) {
        this(broadcastExecutor, SEND_TIMEOUT_MILLIS);
    }

    /** 测试构造：注入 executor + send 超时（短超时快速驱动摘除时序）。 */
    AsmSseBroadcaster(Executor broadcastExecutor, long sendTimeoutMillis) {
        if (sendTimeoutMillis <= 0) {
            throw new IllegalArgumentException("sendTimeoutMillis 必须 > 0，实际：" + sendTimeoutMillis);
        }
        this.broadcastExecutor = broadcastExecutor;
        this.sendTimeoutMillis = sendTimeoutMillis;
        this.sendExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "asm-sse-send");
            t.setDaemon(true);
            return t;
        });
    }

    /** 停止广播与 send executor（@PreDestroy，jar 卸载期调）。 */
    @PreDestroy
    public void shutdown() {
        if (broadcastExecutor instanceof ExecutorService) {
            ((ExecutorService) broadcastExecutor).shutdownNow();
        }
        sendExecutor.shutdownNow();
    }

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
     * 广播单事件 JSON 给所有在线 emitter（AsmSseConsumer 调）——具名 {@link AsmSseEvent#TYPE} 帧。
     *
     * @param json AsmSseEvent 序列化后的 JSON 串
     */
    public void broadcast(String json) {
        broadcastNamed(AsmSseEvent.TYPE, json);
    }

    /**
     * 广播任意具名帧给所有在线 emitter（如控制终态帧 {@code AsmControlCompletedEvent.TYPE}）。
     * 死连接清理语义与 {@link #broadcast} 一致（send 抛任何异常即摘；send 永卡按单条超时摘，均不中断全池）。
     *
     * <p><b>投递即返</b>：send 循环投递到广播 executor（见类 javadoc「广播异步化」），
     * 调用方（总线 lane 线程 / 控制终态路径）不被任何慢客户端的 servlet 写阻塞。</p>
     *
     * @param eventName SSE event 帧名（前端具名 listener 匹配依据）
     * @param json      载荷序列化后的 JSON 串
     */
    public void broadcastNamed(String eventName, String json) {
        submit("广播", () -> sendLoop("广播", em -> em.send(SseEmitter.event()
                .name(eventName)
                .data(json, MediaType.APPLICATION_JSON))));
    }

    /** 心跳：给所有在线 emitter 发 comment 帧（: ping，不进 data 通道，仅保活防代理超时）。
     * 与广播共用同一广播 executor——同一 emitter 的 send 天然串行（消除心跳×广播并发 send 竞态）。 */
    public void heartbeat() {
        submit("心跳", () -> sendLoop("心跳", em -> em.send(SseEmitter.event().comment("ping"))));
    }

    /**
     * 单连接 send 治理循环（广播线程上执行，广播/心跳共用）：
     * 每条 send 投递到 {@code asm-sse-send} helper 线程，广播线程有界等待——
     * 正常返回即送达；抛异常（IOException/RuntimeException，见「死连接清理」）或等待超时
     * （见「单条 send 有界超时」）都摘除该连接，绝不中断对剩余连接的遍历。
     *
     * @param op     操作名（日志用）
     * @param action 单连接帧发送动作（构造 SseEventBuilder 并 send）
     */
    private void sendLoop(String op, SendAction action) {
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            String id = entry.getKey();
            SseEmitter em = entry.getValue();
            Future<?> send;
            try {
                send = sendExecutor.submit(() -> {
                    action.send(em);
                    return null;
                });
            } catch (RejectedExecutionException e) {
                // 合法生命周期边界：@PreDestroy shutdown 后（jar 卸载期）send executor 已关——跳过本连接
                log.debug("[诊断调试] ASM SSE {}send 投递被拒（send executor 已关闭，卸载期丢弃）", op);
                continue;
            }
            try {
                send.get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 174500：对端停读 → servlet 阻塞 flush 永卡（不抛异常，异常摘除路径够不着）——
                // 超时即判死连接摘除，广播线程立即继续下一连接；cancel(true) 中断挂着的 helper
                //（阻塞写停在可中断的 monitor wait），不等容器写超时
                emitters.remove(id);
                send.cancel(true);
                log.warn("[诊断调试] ASM SSE {}send 超时（{}ms），连接 {} 疑似死连接（对端停读），已摘除",
                        op, sendTimeoutMillis, id);
            } catch (ExecutionException e) {
                // send 在 helper 线程抛出（IOException=断连 / RuntimeException=断连实测形态）——摘除语义
                emitters.remove(id);
                log.debug("[诊断调试] ASM SSE {}连接 {} 已断开，移出池：{}", op, id, e.getCause());
            } catch (InterruptedException e) {
                // 广播线程被中断 = 卸载期 shutdownNow——恢复中断标志并停止本轮遍历（后续投递也会被拒）
                Thread.currentThread().interrupt();
                log.debug("[诊断调试] ASM SSE {}send 等待被中断（广播 executor 关闭，卸载期停止遍历）", op);
                break;
            }
        }
    }

    /** 单连接帧发送动作（可抛任何异常——由 sendLoop 统一做超时/摘除治理）。 */
    @FunctionalInterface
    private interface SendAction {
        void send(SseEmitter emitter) throws Exception;
    }

    /**
     * 把 send 循环投递到广播 executor。
     *
     * @param op  操作名（日志用）
     * @param loop send 循环体（异常自持：单连接失败在 loop 内已捕获，不冒泡）
     */
    private void submit(String op, Runnable loop) {
        try {
            broadcastExecutor.execute(loop);
        } catch (RejectedExecutionException e) {
            // 合法生命周期边界：@PreDestroy shutdown 后（jar 卸载期）仍有在途调用方投递——丢弃留痕
            log.debug("[诊断调试] ASM SSE {}投递被拒（广播 executor 已关闭，卸载期丢弃）", op);
        }
    }
}
