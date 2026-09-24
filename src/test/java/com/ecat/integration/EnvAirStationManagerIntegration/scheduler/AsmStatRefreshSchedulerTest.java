package com.ecat.integration.EnvAirStationManagerIntegration.scheduler;

import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmStatAggregationEngine;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ASM 三粒度调度器单测——注入 mock executor 捕获 task（手动 run() 同步触发，无 sleep）：
 * 三个 scheduleAtFixedRate / start 幂等 / scheduledTick 必 catch（task 抛异常不致永久抑制）/
 * tick 窗口与粒度传参正确 / 近窗与 delay 默认值锁定 / 边界对齐数学（secondsToNextBoundary）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmStatRefreshSchedulerTest {

    @Mock
    private AsmStatAggregationEngine engine;
    @Mock
    private ScheduledExecutorService executor;
    @Mock
    private ExecutorService workLane;

    private AsmStatRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AsmStatRefreshScheduler(engine, executor, workLane);
    }

    @Test
    void start_armsThreeGranularityTasks_idempotent() {
        scheduler.start();
        verify(executor, times(3)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));

        scheduler.start();  // 重复 start no-op
        verify(executor, times(3)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void shutdown_cancelsAllArmedTasks_adaptedExecutorNotShutDown() {
        // 引擎车道无独立生命周期：卸载语义=逐个 cancel 自持 future；适配注入的 executor 不归调度器所有
        ScheduledFuture<?> future = org.mockito.Mockito.mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        scheduler.start();
        org.junit.jupiter.api.Assertions.assertEquals(3, scheduler.armedTasks().size());
        scheduler.shutdown();

        verify(future, times(3)).cancel(false);
        verify(executor, never()).shutdownNow();
        verify(executor, never()).shutdown();
    }

    @Test
    void start_armsPeriodsInGranularityOrder() {
        scheduler.start();
        verify(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), eq(60L), eq(TimeUnit.SECONDS));
        verify(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), eq(300L), eq(TimeUnit.SECONDS));
        verify(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void start_armedTasksTickWithDefaultNearWindows() {
        // 计时 runnable 只做投递（工作体进单飞道）——workLane stub 成同步内联执行，
        // 手动 run 捕获的计时 task 即同步走完 tick，无异步等待
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(workLane).execute(any(Runnable.class));

        ArgumentCaptor<Runnable> taskCap = ArgumentCaptor.forClass(Runnable.class);
        scheduler.start();
        verify(executor, times(3)).scheduleAtFixedRate(taskCap.capture(), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        for (Runnable timerTask : taskCap.getAllValues()) {
            timerTask.run();
        }

        ArgumentCaptor<Instant> wsCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> weCap = ArgumentCaptor.forClass(Instant.class);
        verify(engine).materializeGranularity(eq(AsmStatGranularity.MINUTE), wsCap.capture(), weCap.capture(),
                eq("SCHEDULE"), any(Instant.class));
        assertEquals(Duration.ofMinutes(2), Duration.between(wsCap.getValue(), weCap.getValue()),
                "minute task 近窗 2min（raw flush 落 DB 余量）");
        verify(engine).materializeGranularity(eq(AsmStatGranularity.FIVE_MIN), wsCap.capture(), weCap.capture(),
                eq("SCHEDULE"), any(Instant.class));
        assertEquals(Duration.ofMinutes(15), Duration.between(wsCap.getValue(), weCap.getValue()),
                "5min task 近窗 15min（覆盖扩窗 ±1 桶后全子桶）");
        verify(engine).materializeGranularity(eq(AsmStatGranularity.HOUR), wsCap.capture(), weCap.capture(),
                eq("SCHEDULE"), any(Instant.class));
        assertEquals(Duration.ofMinutes(120), Duration.between(wsCap.getValue(), weCap.getValue()),
                "hour task 近窗 120min（minute 子桶全量收口）");
    }

    @Test
    void defaultNearWindowAndDelayConstants_locked() {
        // 近窗/delay 默认是运维口径（raw flush 2s 落库余量 / 等子层就绪），锁默认防漂移
        assertEquals(2, AsmStatRefreshScheduler.MINUTE_WINDOW_MINUTES_DEFAULT);
        assertEquals(15, AsmStatRefreshScheduler.FIVE_MIN_WINDOW_MINUTES_DEFAULT);
        assertEquals(120, AsmStatRefreshScheduler.HOUR_WINDOW_MINUTES_DEFAULT);
        assertEquals(10, AsmStatRefreshScheduler.MINUTE_DELAY_SECONDS_DEFAULT);
        assertEquals(30, AsmStatRefreshScheduler.FIVE_MIN_DELAY_SECONDS_DEFAULT);
        assertEquals(180, AsmStatRefreshScheduler.HOUR_DELAY_SECONDS_DEFAULT);
    }

    @Test
    void secondsToNextBoundary_midInterval_offsetToNextGridEdge() {
        assertEquals(1800L, AsmStatRefreshScheduler.secondsToNextBoundary(
                Duration.ofHours(1), Instant.parse("2026-09-23T10:30:00Z")), "10:30 距 11:00 边界 1800s");
        assertEquals(30L, AsmStatRefreshScheduler.secondsToNextBoundary(
                Duration.ofMinutes(1), Instant.parse("2026-09-23T10:30:30Z")), "秒内偏移 30s");
        assertEquals(210L, AsmStatRefreshScheduler.secondsToNextBoundary(
                Duration.ofMinutes(5), Instant.parse("2026-09-23T10:01:30Z")), "10:01:30 距 10:05 边界 210s");
    }

    @Test
    void secondsToNextBoundary_exactlyOnBoundary_returnsFullPeriod() {
        // 恰落网格边界：下个边界=整周期后（首触发不在启动瞬间打点，返回 period 而非 0）
        assertEquals(60L, AsmStatRefreshScheduler.secondsToNextBoundary(
                Duration.ofMinutes(1), Instant.parse("2026-09-23T10:30:00Z")));
        assertEquals(300L, AsmStatRefreshScheduler.secondsToNextBoundary(
                Duration.ofMinutes(5), Instant.parse("2026-09-23T10:05:00Z")));
        assertEquals(3600L, AsmStatRefreshScheduler.secondsToNextBoundary(
                Duration.ofHours(1), Instant.parse("2026-09-23T11:00:00Z")));
    }

    @Test
    void scheduledTick_engineFailureIsSwallowed_taskStaysArmed() {
        when(engine.materializeGranularity(any(AsmStatGranularity.class), any(Instant.class), any(Instant.class),
                any(String.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("tick boom"));

        // 直接调 tick 体（与 task runnable 同入口）——不抛即「必 catch 保调度连续」
        assertDoesNotThrow(() -> scheduler.tick(AsmStatGranularity.MINUTE, Duration.ofMinutes(2)));
        verify(engine).materializeGranularity(eq(AsmStatGranularity.MINUTE), any(Instant.class),
                any(Instant.class), eq("SCHEDULE"), any(Instant.class));
    }

    @Test
    void tick_minuteWindowCoversTwoMinutes() {
        ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);

        scheduler.tick(AsmStatGranularity.MINUTE, Duration.ofMinutes(2));

        verify(engine).materializeGranularity(eq(AsmStatGranularity.MINUTE), startCap.capture(), endCap.capture(),
                eq("SCHEDULE"), any(Instant.class));
        Duration window = Duration.between(startCap.getValue(), endCap.getValue());
        assertEquals(Duration.ofMinutes(2), window, "minute task 近窗 2min（raw flush 落 DB 余量）");
    }

    @Test
    void tick_windowEndEqualsNow_startIsEndMinusWindow() {
        ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> nowCap = ArgumentCaptor.forClass(Instant.class);

        scheduler.tick(AsmStatGranularity.FIVE_MIN, Duration.ofMinutes(15));

        verify(engine).materializeGranularity(eq(AsmStatGranularity.FIVE_MIN), startCap.capture(), endCap.capture(),
                eq("SCHEDULE"), nowCap.capture());
        assertEquals(endCap.getValue(), nowCap.getValue(), "审计时间源 now 与窗口右端 we 是同一 Instant");
        assertEquals(endCap.getValue().minus(Duration.ofMinutes(15)), startCap.getValue(), "ws = we - window");
    }

    @Test
    void shutdown_withInjectedExecutor_doesNotCloseIt() {
        // 测试注入 executor 不归调度器拥有——shutdown 只 cancel future 不关 executor（测试自管生命周期）
        ScheduledFuture<?> future = org.mockito.Mockito.mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        scheduler.start();
        scheduler.shutdown();
        scheduler.shutdown();  // 幂等
        verify(future, times(3)).cancel(false);
        verify(executor, never()).shutdownNow();
    }
}
