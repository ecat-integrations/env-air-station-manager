package com.ecat.integration.EnvAirStationManagerIntegration.scheduler;

import com.ecat.core.EcatCore;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Task.runner.HostedExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AsmLanes 单测（确定性，禁 sleep；双轨统一）：到点
 * tick 只投递（工作体在道 worker 线程执行，不内联计时线程）/ 道拒绝（REE）记账不逃逸
 * （计时永不注销）/ 接线契约（未接线 ISE、幂等 keep-first、null 拒绝）/ 计时解析
 * （显式 bind → core biz 池；无 core 且未 bind 显式抛——本地兜底双轨已删）。
 * 计时行为经 mock ScheduledExecutorService 捕获 + 真实 bounded 道驱动，断言的是
 * 「计时注册 + 投递经道」这一接线前提，非猜测。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmLanesTest {

    @Mock
    private ScheduledExecutorService bizTimer;

    private ExecutorService workLane;

    @AfterEach
    void tearDown() {
        AsmLanes.unbind();
        AsmLanes.unwireWorkLane();
        if (workLane != null) {
            workLane.shutdownNow();
        }
    }

    /** 接线测试道（真实 bounded(1) 单飞池，假宿主；生命周期测试自管）。 */
    private ExecutorService wiredLane() {
        workLane = HostedExecutors.bounded(1, action -> { });
        AsmLanes.wireWorkLane(workLane);
        return workLane;
    }

    /** 车道登记：atFixedRate 在计时器注册 tick，tick 触发时工作体经道 worker 执行（不内联计时线程）。 */
    @Test
    void lane_registersTimerAndRoutesWorkThroughLaneWorker() throws Exception {
        java.util.List<Runnable> timerTicks = new CopyOnWriteArrayList<>();
        when(bizTimer.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    timerTicks.add(inv.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        AsmLanes.bind(bizTimer);
        ExecutorService lane = wiredLane();

        AsmLanes.Lane laneView = AsmLanes.resolve();

        CountDownLatch workRan = new CountDownLatch(1);
        AtomicReference<Thread> runner = new AtomicReference<>();
        laneView.atFixedRate(() -> {
            runner.set(Thread.currentThread());
            workRan.countDown();
        }, 1L, 2L, TimeUnit.SECONDS);

        assertEquals(1, timerTicks.size(), "tick 应注册到绑定计时器");

        CountDownLatch tickReturned = new CountDownLatch(1);
        Thread observer = new Thread(() -> {
            timerTicks.get(0).run();
            tickReturned.countDown();
        }, "biz-tick-thread");
        observer.start();
        assertTrue(tickReturned.await(5, TimeUnit.SECONDS), "tick 应已返回（投递 O(1) 即返）");
        assertTrue(workRan.await(5, TimeUnit.SECONDS), "工作体应在道 worker 执行");
        assertNotNull(runner.get());
        // 工作体在道 worker 执行（非触发 tick 的计时线程）
        assertFalse(runner.get().equals(observer), "工作体不得内联在计时线程执行");
    }

    /** tick 投递即返：先占住单飞道，跑 tick（投递）后工作体必然未执行（确定性：道被占）。 */
    @Test
    void tickSubmitsWithoutBlocking_timerThreadFree() throws Exception {
        java.util.List<Runnable> timerTicks = new CopyOnWriteArrayList<>();
        when(bizTimer.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    timerTicks.add(inv.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        AsmLanes.bind(bizTimer);
        ExecutorService lane = wiredLane();

        // 占道：阻塞任务持住唯一 worker
        CountDownLatch laneOccupied = new CountDownLatch(1);
        CountDownLatch releaseLane = new CountDownLatch(1);
        lane.execute(() -> {
            laneOccupied.countDown();
            try {
                releaseLane.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(laneOccupied.await(5, TimeUnit.SECONDS));

        AsmLanes.Lane laneView = AsmLanes.resolve();
        CountDownLatch workRan = new CountDownLatch(1);
        laneView.atFixedRate(workRan::countDown, 0L, 1L, TimeUnit.SECONDS);

        long before = System.nanoTime();
        timerTicks.get(0).run();   // 到点 tick：投递 O(1) 即返（道忙不影响计时线程）
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(5), "tick 投递应即返（不阻塞计时线程）");
        assertEquals(1, workRan.getCount(), "道忙期间工作体不得已执行");

        releaseLane.countDown();
        assertTrue(workRan.await(5, TimeUnit.SECONDS), "放行后工作体应执行");
    }

    /** 道拒绝（关停同步抛 REE）由投递记账吞掉——tick 不抛异常，计时永不注销。 */
    @Test
    void laneRejection_accountedNotThrown() {
        java.util.List<Runnable> timerTicks = new CopyOnWriteArrayList<>();
        when(bizTimer.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    timerTicks.add(inv.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        AsmLanes.bind(bizTimer);
        ExecutorService shutDown = HostedExecutors.bounded(1, action -> { });
        shutDown.shutdownNow();
        AsmLanes.wireWorkLane(shutDown);

        AsmLanes.Lane lane = AsmLanes.resolve();
        lane.atFixedRate(() -> { }, 0L, 1L, TimeUnit.SECONDS);

        // tick 触发遇 REE：不得抛（投递记账即弃，下周期自愈）
        timerTicks.get(0).run();
    }

    /** 生产解析分支：运行中的 core（EcatCore.getInstance() 非空）→ biz 池计时 + 接线道（仿
     *  LogicDeviceDelaySchedulerTest 的 setInstance 手法）。 */
    @Test
    void resolve_coreInstancePresent_bizPoolTimerAndWiredLane() {
        EcatCore previous = EcatCore.getInstance();
        TaskManager taskManager = new TaskManager();
        EcatCore mockCore = mock(EcatCore.class);
        when(mockCore.getTaskManager()).thenReturn(taskManager);
        try {
            EcatCore.setInstance(mockCore);
            ExecutorService lane = wiredLane();

            AsmLanes.Lane resolved = AsmLanes.resolve();

            assertSame(taskManager.getBizScheduler(), resolved.getTimer(), "计时器应为 core 业务计时池");
            assertSame(lane, resolved.getWorkLane(), "工作道应为接线注入的模块单飞道");
        } finally {
            EcatCore.setInstance(previous);
            taskManager.shutdownAll();
        }
    }

    /** 双轨已删：无 bind 且无运行中的 core（单测 JVM）→ 显式抛 ISE，不再本地兜底。 */
    @Test
    void resolve_noCoreNoBind_throwsIse() {
        EcatCore previous = EcatCore.getInstance();
        try {
            EcatCore.setInstance(null);
            assertThrows(IllegalStateException.class, () -> AsmLanes.resolve());
        } finally {
            EcatCore.setInstance(previous);
        }
    }

    /** 接线契约：wireWorkLane(null) 拒绝（道的创建必须过宿主）。 */
    @Test
    void wireWorkLane_nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> AsmLanes.wireWorkLane(null));
    }

    /** 接线契约：幂等 keep-first（disable→re-enable 重跑 onStart 复用既有道）。 */
    @Test
    void wireWorkLane_idempotentKeepsFirst() {
        ExecutorService first = HostedExecutors.bounded(1, action -> { });
        ExecutorService second = HostedExecutors.bounded(1, action -> { });
        try {
            AsmLanes.wireWorkLane(first);
            AsmLanes.wireWorkLane(second);
            // 经 resolve 侧观察（bind 计时器后 resolve 返回接线道）
            AsmLanes.bind(bizTimer);
            assertSame(first, AsmLanes.resolve().getWorkLane(), "重复接线须保留既有道（keep-first）");
        } finally {
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    /** 适配注入 executor（laneOf）：计时与工作道均不归车道所有（测试自管，本类无收尾面）。 */
    @Test
    void laneOf_adaptsInjectedTimerAndWorkLane() {
        ScheduledExecutorService injected = mock(ScheduledExecutorService.class);
        ExecutorService injectedWork = mock(ExecutorService.class);
        AsmLanes.Lane lane = AsmLanes.laneOf(injected, injectedWork);
        assertSame(injected, lane.getTimer());
        assertSame(injectedWork, lane.getWorkLane());
    }

    @Test
    void bind_nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> AsmLanes.bind(null));
    }
}
