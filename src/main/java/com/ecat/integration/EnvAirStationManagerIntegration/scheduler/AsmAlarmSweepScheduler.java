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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
 * <p><b>车道登记（计时归 biz 池+HostedExecutors 工作道）</b>：
 * 生产 executor 退役自建单线程池，周期 sweep 经 {@link AsmLanes#resolve()} 登记——biz 池计时 +
 * 工作体进模块单飞道（与三粒度物化 tick 同道串行互斥——sweep 是轻量 DB UPDATE，物化窗口
 * 有界，串行无饥饿风险）。</p>
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
    private final AsmLanes.Lane lane;
    private ScheduledFuture<?> scheduledFuture;

    /** 生产构造：@Service 注入 + @Value 读窗口配置；周期 sweep 登记 module:asm 车道（biz 池计时+单飞道串行）。 */
    @Autowired
    public AsmAlarmSweepScheduler(AsmAlarmRecordMapper recordMapper,
                                  AsmAlarmRegistry registry,
                                  @Value("${asm.alarm.heartbeat-window-minutes:5}") int heartbeatWindowMinutes) {
        this(recordMapper, registry, heartbeatWindowMinutes, AsmLanes.resolve());
    }

    /** 测试构造：注入 mock 计时 executor 捕获 task 手动触发 + 工作道，不拥有任一 executor（测试自管）。 */
    AsmAlarmSweepScheduler(AsmAlarmRecordMapper recordMapper, AsmAlarmRegistry registry,
                           int heartbeatWindowMinutes, ScheduledExecutorService executor,
                           ExecutorService workLane) {
        this(recordMapper, registry, heartbeatWindowMinutes, AsmLanes.laneOf(executor, workLane));
    }

    AsmAlarmSweepScheduler(AsmAlarmRecordMapper recordMapper, AsmAlarmRegistry registry,
                           int heartbeatWindowMinutes, AsmLanes.Lane lane) {
        this.recordMapper = recordMapper;
        this.registry = registry;
        this.heartbeatWindowMinutes = heartbeatWindowMinutes;
        this.lane = lane;
    }

    /** 启动周期 sweep（integration onStart 调一次；重复调幂等取消旧 task 重arm）。 */
    public synchronized void start() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        scheduledFuture = lane.atFixedRate(this::sweepSafely,
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

    /** 模块卸载取消周期 task（计时器/工作道不停——工作道拆卸挂集成 onRemove sweep，
     * 重 arm 走 start）。 */
    @PreDestroy
    public void shutdown() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
    }
}
