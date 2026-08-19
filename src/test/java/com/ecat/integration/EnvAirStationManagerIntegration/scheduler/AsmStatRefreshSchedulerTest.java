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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ASM 三粒度调度器单测——注入 mock executor 捕获 task（手动 run() 同步触发，无 sleep）：
 * 三个 scheduleAtFixedRate / start 幂等 / scheduledTick 必 catch（task 抛异常不致永久抑制）/
 * tick 窗口与粒度传参正确。
 */
@ExtendWith(MockitoExtension.class)
class AsmStatRefreshSchedulerTest {

    @Mock
    private AsmStatAggregationEngine engine;
    @Mock
    private ScheduledExecutorService executor;

    private AsmStatRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AsmStatRefreshScheduler(engine, executor);
    }

    @Test
    void start_armsThreeGranularityTasks_idempotent() {
        scheduler.start();
        verify(executor, times(3)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));

        scheduler.start();  // 重复 start no-op
        verify(executor, times(3)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void start_armsPeriodsInGranularityOrder() {
        scheduler.start();
        verify(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), eq(60L), eq(TimeUnit.SECONDS));
        verify(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), eq(300L), eq(TimeUnit.SECONDS));
        verify(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), eq(3600L), eq(TimeUnit.SECONDS));
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
    void shutdown_withInjectedExecutor_doesNotCloseIt() {
        // 测试注入 executor 不归调度器拥有（ownsExecutor=false）——shutdown 不关（测试自管生命周期）
        scheduler.start();
        scheduler.shutdown();
        scheduler.shutdown();  // 幂等
        verify(executor, never()).shutdownNow();
    }
}
