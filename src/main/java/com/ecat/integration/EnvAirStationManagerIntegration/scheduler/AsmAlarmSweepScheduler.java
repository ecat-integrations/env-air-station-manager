package com.ecat.integration.EnvAirStationManagerIntegration.scheduler;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASM 报警心跳 sweep 调度器（镜像 ADM AdmAlarmExpiryScheduler，窗口全局统一非 per-rule）。
 *
 * <p>1min 周期闭所有 {@code ACTIVE 且 now - last_breach_time > 心跳窗} 的行——无论值回正常、
 * 站房静默还是重启孤儿，超窗未续期即闭（恢复有窗内滞后，取舍同 ADM BC-E1）。窗口默认 5 分钟
 * （保持原 alarmCache 去重节奏），可配 {@code asm.alarm.heartbeat-window-minutes}；
 * <b>与规则 durationMinutes 无关</b>（那是「持续多久才触发」语义）。</p>
 *
 * <p>闭单批量 UPDATE（status→INACTIVE + end_time=now）后同步摘 {@link AsmAlarmRegistry} 徽章槽
 * （registry 是 DB 非权威镜像）。sweep 体内必 catch（scheduleAtFixedRate 抛未捕获异常抑制后续 task，
 * 同 AsmStatRefreshScheduler 纪律）。测试注入 executor + 手动调 {@link #doSweep}，禁 sleep。</p>
 *
 * @author coffee
 */
@Service
public class AsmAlarmSweepScheduler {

    /** sweep 周期（分钟）。 */
    static final long SWEEP_PERIOD_MINUTES = 1L;
    /** 初始延迟（分钟）：core 起后等 1min 再首 sweep（registry 重建 / consumer 就绪）。 */
    static final long INITIAL_DELAY_MINUTES = 1L;
    /** 默认心跳窗（分钟）——对齐原 alarmCache 5min 去重节奏。 */
    static final int HEARTBEAT_WINDOW_MINUTES_DEFAULT = 5;

    private final Log log = LogFactory.getLogger(getClass());

    private final AsmAlarmRecordMapper recordMapper;
    private final AsmAlarmRegistry registry;
    private final int heartbeatWindowMinutes;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private ScheduledFuture<?> scheduledFuture;

    /** 生产构造：@Service 注入 + @Value 读窗口配置，自建单线程命名守护线程池。 */
    @Autowired
    public AsmAlarmSweepScheduler(AsmAlarmRecordMapper recordMapper,
                                  AsmAlarmRegistry registry,
                                  @Value("${asm.alarm.heartbeat-window-minutes:5}") int heartbeatWindowMinutes) {
        this(recordMapper, registry, heartbeatWindowMinutes,
                Executors.newSingleThreadScheduledExecutor(namedThreadFactory("asm-alarm-sweep")), true);
    }

    /** 测试构造：注入 mock executor 捕获 task 手动触发，不拥有 executor（测试自管）。 */
    AsmAlarmSweepScheduler(AsmAlarmRecordMapper recordMapper, AsmAlarmRegistry registry,
                           int heartbeatWindowMinutes, ScheduledExecutorService executor) {
        this(recordMapper, registry, heartbeatWindowMinutes, executor, false);
    }

    private AsmAlarmSweepScheduler(AsmAlarmRecordMapper recordMapper, AsmAlarmRegistry registry,
                                   int heartbeatWindowMinutes, ScheduledExecutorService executor,
                                   boolean ownsExecutor) {
        this.recordMapper = recordMapper;
        this.registry = registry;
        this.heartbeatWindowMinutes = heartbeatWindowMinutes;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** 启动周期 sweep（integration onStart 调一次；重复调幂等取消旧 task 重arm）。 */
    public synchronized void start() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        scheduledFuture = executor.scheduleAtFixedRate(this::sweepSafely,
                INITIAL_DELAY_MINUTES, SWEEP_PERIOD_MINUTES, TimeUnit.MINUTES);
        log.info("[诊断调试] ASM 报警 sweep 已启动: 周期={}min 心跳窗={}min",
                SWEEP_PERIOD_MINUTES, heartbeatWindowMinutes);
    }

    /** sweep 体——必 catch 保调度连续（DB 故障本轮跳过，下轮自愈）。 */
    void sweepSafely() {
        try {
            int closed = doSweep(Instant.now());
            if (closed > 0) {
                log.info("[诊断调试] ASM 报警 sweep 闭 {} 条过期 ACTIVE", closed);
            }
        } catch (Exception e) {
            log.error("[诊断调试] ASM 报警 sweep 执行失败（下轮继续）: ", e);
        }
    }

    /**
     * sweep 核心（包级可见供单测直接调，传虚拟 now）。
     *
     * @return 闭掉的行数
     */
    int doSweep(Instant now) {
        List<AsmAlarmRecord> activeRows = recordMapper.selectAllActive();
        if (activeRows == null || activeRows.isEmpty()) {
            return 0;
        }
        Instant cutoff = now.minus(Duration.ofMinutes(heartbeatWindowMinutes));
        List<Long> expiredIds = new ArrayList<>();
        for (AsmAlarmRecord row : activeRows) {
            Instant lastBreach = row.getLastBreachTime() != null ? row.getLastBreachTime() : row.getStartTime();
            if (lastBreach != null && lastBreach.isBefore(cutoff)) {
                expiredIds.add(row.getId());
            }
        }
        if (expiredIds.isEmpty()) {
            return 0;
        }
        int closed = recordMapper.closeBatch(expiredIds, now);
        // DB 闭成功后同步摘 registry 徽章槽（非权威镜像保一致）
        for (AsmAlarmRecord row : activeRows) {
            if (expiredIds.contains(row.getId())) {
                registry.remove(row.getLogicDeviceUniqueId(), row.getAttrId(), row.getAlarmType());
            }
        }
        return closed;
    }

    /** 模块卸载取消周期任务 + 关自建线程池（注入的测试 executor 不关，测试自管）。 */
    @PreDestroy
    public void shutdown() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        if (ownsExecutor && executor != null) {
            executor.shutdownNow();
        }
    }

    /** 命名守护线程工厂（线程名 asm-alarm-sweep-N）。 */
    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
