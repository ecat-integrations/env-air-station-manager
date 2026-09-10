package com.ecat.integration.EnvAirStationManagerIntegration.scheduler;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.anyLong;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报警心跳 sweep 调度器 TDD：窗口外闭单摘槽 / 窗口内不闭 / 单轮失败吞保连续。mock mapper + 注入
 * executor（手动触发，禁 sleep）；doSweep 传显式 now（虚拟时钟语义）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsmAlarmSweepSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final String UID = "logicdevice_station.th";

    @Mock
    private AsmAlarmRecordMapper mapper;
    @Mock
    private ScheduledExecutorService executor;

    private AsmAlarmRegistry registry;
    private AsmAlarmSweepScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new AsmAlarmRegistry();
        scheduler = new AsmAlarmSweepScheduler(mapper, registry, 5, executor,
                org.mockito.Mockito.mock(ExecutorService.class));
        lenient().when(mapper.closeBatch(anyList(), any(Instant.class))).thenReturn(1);
    }

    private static AsmAlarmRecord activeRow(long id, Instant start, Instant lastBreach) {
        return AsmAlarmRecord.builder().id(id).alarmType("room_temp_abnormal").ruleName("t")
                .logicDeviceUniqueId(UID).attrId("temperature").severity("0")
                .startTime(start).endTime(null).status("ACTIVE").lastBreachTime(lastBreach).build();
    }

    @Test
    void shutdown_cancelsSweepTask_adaptedExecutorNotShutDown() {
        ScheduledFuture<?> future = org.mockito.Mockito.mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        scheduler.start();
        scheduler.shutdown();

        verify(future).cancel(false);
        verify(executor, never()).shutdownNow();
        verify(executor, never()).shutdown();
    }

    @Test
    void sweep_expiredActive_closedAndSlotRemoved() {
        registry.put(new AsmAlarmRegistry.ActiveAlarm(UID, "temperature", "room_temp_abnormal", "t", T0, T0));
        when(mapper.selectAllActive()).thenReturn(Collections.singletonList(activeRow(7L, T0, T0)));

        int closed = scheduler.doSweep(T0.plusSeconds(6 * 60));      // last_breach + 6min > 5min 窗

        assertEquals(1, closed);
        verify(mapper).closeBatch(Collections.singletonList(7L), T0.plusSeconds(6 * 60));
        assertFalse(registry.hasActive(UID));                        // 徽章槽同步摘
    }

    @Test
    void sweep_withinWindow_notClosed() {
        when(mapper.selectAllActive()).thenReturn(
                Collections.singletonList(activeRow(7L, T0, T0.plusSeconds(3 * 60))));

        assertEquals(0, scheduler.doSweep(T0.plusSeconds(6 * 60)));   // 3min 前续期 < 5min 窗 → 不闭
        verify(mapper, never()).closeBatch(anyList(), any(Instant.class));
    }

    @Test
    void sweepSafely_mapperFailure_swallowed_schedulerSurvives() {
        when(mapper.selectAllActive()).thenThrow(new RuntimeException("db down"));

        scheduler.sweepSafely();                                      // 不抛（保 scheduleAtFixedRate 连续）
        scheduler.sweepSafely();
        verify(mapper, times(2)).selectAllActive();
    }
}
