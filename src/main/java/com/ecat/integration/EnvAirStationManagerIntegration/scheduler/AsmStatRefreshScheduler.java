package com.ecat.integration.EnvAirStationManagerIntegration.scheduler;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmStatAggregationEngine;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASM 三粒度定时物化调度器（机制同 ADM AdmStatRefreshScheduler，三级无 day）。
 *
 * <p>三个 {@code scheduleAtFixedRate} task，各按粒度周期对齐边界 + delay 触发（等子层就绪），
 * 调 {@link AsmStatAggregationEngine#materializeGranularity}（单粒度，引擎内按 series 配置 mode/mask 门控）：
 * <table>
 *   <caption>定时物化时机（默认，asm.stat.*-delay-seconds 可配）</caption>
 *   <tr><th>粒度</th><th>触发</th><th>近窗</th><th>就绪依据</th></tr>
 *   <tr><td>minute</td><td>每分钟 :10s</td><td>[now-2min, now]</td><td>raw flush 2s 落 DB 后留 8s</td></tr>
 *   <tr><td>5min</td><td>每 5min 边界 +30s</td><td>[now-15min, now]</td><td>等 minute 子桶就绪</td></tr>
 *   <tr><td>hour</td><td>每整点 +180s（:03）</td><td>[now-2h, now]</td><td>等 minute 子桶全量收口</td></tr>
 * </table></p>
 *
 * <p><b>scheduledTick 必 catch</b>：{@code scheduleAtFixedRate} task 抛未捕获异常则该 task 后续全抑制——
 * {@link #tick} 包 try/catch（失败记 error 后吞，物化幂等下个周期续算）。<b>start() 幂等</b>（synchronized + started）。
 * {@code shutdown()} 关自建 executor（测试注入的 executor 不关，测试自管）。</p>
 *
 * @author coffee
 */
@Service
public class AsmStatRefreshScheduler {

    // ===== 近窗默认（分钟；覆盖扩窗 ±1 桶后全子桶）=====
    static final int MINUTE_WINDOW_MINUTES_DEFAULT = 2;
    static final int FIVE_MIN_WINDOW_MINUTES_DEFAULT = 15;
    static final int HOUR_WINDOW_MINUTES_DEFAULT = 120;
    // ===== 各粒度 delay（边界后多少秒触发，等子层就绪）=====
    static final int MINUTE_DELAY_SECONDS_DEFAULT = 10;
    static final int FIVE_MIN_DELAY_SECONDS_DEFAULT = 30;
    static final int HOUR_DELAY_SECONDS_DEFAULT = 180;

    private final Log log = LogFactory.getLogger(getClass());

    private final AsmStatAggregationEngine engine;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private final int minuteDelaySeconds;
    private final int fiveMinDelaySeconds;
    private final int hourDelaySeconds;

    private volatile boolean started = false;

    /** 生产构造：@Service 注入 + @Value 读 delay 配置，自建 3 线程命名守护线程池。 */
    @Autowired
    public AsmStatRefreshScheduler(AsmStatAggregationEngine engine,
                                   @Value("${asm.stat.minute-delay-seconds:10}") int minuteDelaySeconds,
                                   @Value("${asm.stat.five-min-delay-seconds:30}") int fiveMinDelaySeconds,
                                   @Value("${asm.stat.hour-delay-seconds:180}") int hourDelaySeconds) {
        this(engine, Executors.newScheduledThreadPool(3, namedThreadFactory("asm-stat-refresh")),
                true, minuteDelaySeconds, fiveMinDelaySeconds, hourDelaySeconds);
    }

    /** 测试构造（默认 delay）：注入 mock/虚拟 executor 捕获 task 手动 run()，无 sleep；不拥有 executor。 */
    AsmStatRefreshScheduler(AsmStatAggregationEngine engine, ScheduledExecutorService executor) {
        this(engine, executor, false,
                MINUTE_DELAY_SECONDS_DEFAULT, FIVE_MIN_DELAY_SECONDS_DEFAULT, HOUR_DELAY_SECONDS_DEFAULT);
    }

    private AsmStatRefreshScheduler(AsmStatAggregationEngine engine, ScheduledExecutorService executor,
                                    boolean ownsExecutor, int minuteDelaySeconds, int fiveMinDelaySeconds,
                                    int hourDelaySeconds) {
        this.engine = engine;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.minuteDelaySeconds = minuteDelaySeconds;
        this.fiveMinDelaySeconds = fiveMinDelaySeconds;
        this.hourDelaySeconds = hourDelaySeconds;
    }

    /** 启动三粒度定时物化（core onStart 调一次）；幂等：已 arm 则 no-op。 */
    public synchronized void start() {
        if (started) {
            log.debug("[诊断调试] ASM 物化调度器已启动过，跳过重复 arm");
            return;
        }
        started = true;
        scheduleTask(AsmStatGranularity.MINUTE, Duration.ofMinutes(1), minuteDelaySeconds,
                Duration.ofMinutes(MINUTE_WINDOW_MINUTES_DEFAULT));
        scheduleTask(AsmStatGranularity.FIVE_MIN, Duration.ofMinutes(5), fiveMinDelaySeconds,
                Duration.ofMinutes(FIVE_MIN_WINDOW_MINUTES_DEFAULT));
        scheduleTask(AsmStatGranularity.HOUR, Duration.ofHours(1), hourDelaySeconds,
                Duration.ofMinutes(HOUR_WINDOW_MINUTES_DEFAULT));
        log.info("[诊断调试] ASM 物化调度器已启动: 3 粒度 task(minute={}s/5min={}s/hour={}s delay)",
                minuteDelaySeconds, fiveMinDelaySeconds, hourDelaySeconds);
    }

    /** 注册单粒度 task：对齐 period 下个边界 + delay 后首触发，之后按 period 固定速率。 */
    private void scheduleTask(AsmStatGranularity gran, Duration period, int delaySeconds, Duration window) {
        long initialDelaySec = secondsToNextBoundary(period, Instant.now()) + delaySeconds;
        executor.scheduleAtFixedRate(() -> tick(gran, window), initialDelaySec, period.getSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 单粒度物化 tick（task 体）——近窗增量调引擎；必 catch 保调度连续。
     * 包级可见：测试与 task 同入口，确定性验证窗口/异常吞咽。
     */
    void tick(AsmStatGranularity gran, Duration window) {
        try {
            Instant end = Instant.now();
            engine.materializeGranularity(gran, end.minus(window), end, "SCHEDULE", end);
        } catch (RuntimeException e) {
            log.error("[诊断调试] ASM {} 物化 tick 失败，已吞保调度连续: ", gran.dbCode(), e);
        }
    }

    /** 对齐 period 下个网格边界还剩多少秒（如 period=1h、now=10:30 → 1800s）。 */
    static long secondsToNextBoundary(Duration period, Instant now) {
        long periodSec = period.getSeconds();
        long nowSec = now.getEpochSecond();
        long nextBoundarySec = ((nowSec / periodSec) + 1) * periodSec;
        return nextBoundarySec - nowSec;
    }

    /** 模块卸载/暂停释放自建线程池（注入的测试 executor 不关）；幂等。 */
    @PreDestroy
    public void shutdown() {
        if (ownsExecutor && executor != null) {
            executor.shutdownNow();
            log.info("[诊断调试] ASM 物化调度器已关闭");
        }
    }

    /** 命名守护线程工厂（线程名 asm-stat-refresh-N；daemon 防 JVM 退出阻塞）。 */
    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
