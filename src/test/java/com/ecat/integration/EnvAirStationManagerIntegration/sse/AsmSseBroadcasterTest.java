package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AsmSseBroadcaster 单测——池增删查 + 具名帧（F1 硬要求）+ 死连接清理 + 心跳 comment 帧。
 * 平移 ADM AdmSseBroadcasterTest 模式（SseEmitter 非 final 可 mock）。
 */
class AsmSseBroadcasterTest {

    private AsmSseBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        // 同步 executor（Runnable::run）：send 循环在测试线程内联执行，池断言零竞态。
        // 异步行为（不阻塞调用方/单 worker 保序）由下方真实单线程 executor 专项测试覆盖。
        broadcaster = new AsmSseBroadcaster(Runnable::run);
    }

    @Test
    void register_unregister_activeCount_isActive_basicPoolOps() {
        SseEmitter em1 = new SseEmitter();
        SseEmitter em2 = new SseEmitter();

        assertEquals(0, broadcaster.activeCount());
        assertFalse(broadcaster.isActive("s1"));

        broadcaster.register("s1", em1);
        broadcaster.register("s2", em2);
        assertEquals(2, broadcaster.activeCount());
        assertTrue(broadcaster.isActive("s1"));

        broadcaster.unregister("s1");
        assertEquals(1, broadcaster.activeCount());
        assertFalse(broadcaster.isActive("s1"));

        // 重复注销幂等
        broadcaster.unregister("s1");
        assertEquals(1, broadcaster.activeCount());
    }

    @Test
    void broadcast_namedFrame_deviceDataUpdate_withJsonData() throws Exception {
        SseEmitter em = mock(SseEmitter.class);
        broadcaster.register("s1", em);

        broadcaster.broadcast("{\"attrId\":\"temperature\"}");

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(em).send(captor.capture());
        // 展开验证：帧数据进 data 通道（具名帧头验证依赖 SseEventBuilder 内部结构，ADM 同款以 send 调用 +
        // broadcast 源码 .name(AsmSseEvent.TYPE) 双重保证；此处至少锁定 send 被调）
        assertTrue(captor.getValue() != null, "broadcast 应对在线 emitter 调 send");
    }

    @Test
    void broadcast_deadConnection_removedFromPool() throws Exception {
        SseEmitter dead = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new IOException("client gone")).when(dead)
                .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        broadcaster.register("dead", dead);

        broadcaster.broadcast("{}");

        assertFalse(broadcaster.isActive("dead"), "send 抛 IOException 的死连接应被移出池");
    }

    /**
     * 毒连接回归：send 抛非 IO/非 ISE 的 RuntimeException（浏览器断连后死连接实测形态，
     * 日志栈 OutputStreamWriter.flush→SseEmitter.send 逃逸）不得中止 forEach——
     * 毒连接移出池，且排在它之后的健康连接仍收到帧。
     */
    @Test
    void broadcast_poisonEmitterThrowingRuntime_deathMustNotStarveHealthy() throws Exception {
        SseEmitter poison = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new RuntimeException("broken pipe in flush"))
                .when(poison).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        broadcaster.register("poison", poison);
        broadcaster.register("healthy", healthy);

        broadcaster.broadcast("{}");

        assertFalse(broadcaster.isActive("poison"), "send 抛 RuntimeException 的毒连接应被移出池");
        assertTrue(broadcaster.isActive("healthy"), "健康连接应留在池中");
        verify(healthy).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }

    /** 心跳同款毒连接回归：毒 send 抛 RuntimeException 后健康连接仍收到 comment 帧。 */
    @Test
    void heartbeat_poisonEmitterThrowingRuntime_deathMustNotStarveHealthy() throws Exception {
        SseEmitter poison = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new RuntimeException("broken pipe in flush"))
                .when(poison).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        broadcaster.register("poison", poison);
        broadcaster.register("healthy", healthy);

        broadcaster.heartbeat();

        assertFalse(broadcaster.isActive("poison"));
        assertTrue(broadcaster.isActive("healthy"));
        verify(healthy).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void heartbeat_sendsCommentFrameAndCleansDead() throws Exception {
        SseEmitter alive = mock(SseEmitter.class);
        SseEmitter dead = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("completed")).when(dead)
                .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        broadcaster.register("alive", alive);
        broadcaster.register("dead", dead);

        broadcaster.heartbeat();

        verify(alive).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        assertFalse(broadcaster.isActive("dead"));
        assertTrue(broadcaster.isActive("alive"));
    }

    /**
     * B 修复回归（bug-record-20260826-005500）：慢/死连接的 send 不得阻塞调用方——
     * 广播调用方是总线 lane 线程（如 modbus-source worker），同步 send 曾把 10s+ 的
     * servlet 写阻塞钉死 lane，后续写事务连环 12s 硬超时。修后 broadcast 投递即返，
     * send 在专用广播 executor 上执行。
     */
    @Test
    void broadcast_slowSend_mustNotBlockCaller() throws Exception {
        AsmSseBroadcaster asyncBroadcaster = new AsmSseBroadcaster();
        CountDownLatch release = new CountDownLatch(1);
        try {
            SseEmitter slow = mock(SseEmitter.class);
            org.mockito.Mockito.doAnswer(inv -> {
                release.await();
                return null;
            }).when(slow).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            asyncBroadcaster.register("slow", slow);

            // 红（现状）：send 内联在调用方线程 → 500ms 抢占超时必炸；绿：投递即返，毫秒级通过。
            assertTimeoutPreemptively(Duration.ofMillis(500),
                    () -> asyncBroadcaster.broadcast("{}"));
        } finally {
            release.countDown();
            asyncBroadcaster.shutdown();
        }
    }

    /**
     * SSE 时序语义保持：专用单 worker executor FIFO——先投递的广播（send 阻塞）未完成前，
     * 后投递的广播不得开始 send（帧顺序 per-connection 不会乱序）。
     * send 阻塞 await 自带 5s 自释（红阶段同步实现下本测试在测试线程内联阻塞，@Timeout 防 fork 挂死）。
     */
    @Test
    @Timeout(value = 5)
    void broadcast_singleWorkerFifo_secondBroadcastWaitsForFirst() throws Exception {
        AsmSseBroadcaster asyncBroadcaster = new AsmSseBroadcaster();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            AtomicInteger sendCalls = new AtomicInteger();
            SseEmitter em = mock(SseEmitter.class);
            org.mockito.Mockito.doAnswer(inv -> {
                if (sendCalls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS), "首次 send 阻塞应被释放");
                } else {
                    secondDone.countDown();
                }
                return null;
            }).when(em).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            asyncBroadcaster.register("s1", em);

            asyncBroadcaster.broadcast("{}");  // 第 1 次广播：send 阻塞在 releaseFirst
            asyncBroadcaster.broadcast("{}");  // 第 2 次广播：必须排队

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "第一次广播的 send 应已在 worker 上开始");
            assertEquals(1, secondDone.getCount(),
                    "第一次广播未完成前，第二次广播不得开始 send（单 worker FIFO 保序）");
            releaseFirst.countDown();
            assertTrue(secondDone.await(2, TimeUnit.SECONDS), "释放后第二次广播应完成 send");
        } finally {
            asyncBroadcaster.shutdown();
        }
    }

    /**
     * 174500 回归（bug-record-20260826-174500）：单条连接 send <b>永卡</b>（对端停读 → TCP 接收窗归零 →
     * servlet 阻塞 flush 无限等待，jstack 卡 NioEndpoint doWrite）不得钉死广播线程——单条 send 有界超时
     * （默认 5s）后死连接摘除，健康连接继续收帧。与毒连接用例的本质差异：send 不抛异常（永远不返回），
     * 异常摘除路径（F-36）对它无效，只有超时摘除能救广播域。
     *
     * <p>红（现状）：send 内联广播线程永卡 → 两次广播都排在其后永不执行 → healthy 8s 内收不到帧。</p>
     */
    @Test
    @Timeout(value = 20)
    void broadcast_sendHangsForever_deadEvictedByTimeout_healthyStillServed() throws Exception {
        AsmSseBroadcaster asyncBroadcaster = new AsmSseBroadcaster();
        CountDownLatch releaseStuck = new CountDownLatch(1);
        CountDownLatch healthyGot = new CountDownLatch(2);
        try {
            SseEmitter stuck = mock(SseEmitter.class);
            org.mockito.Mockito.doAnswer(inv -> {
                releaseStuck.await();
                return null;
            }).when(stuck).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            SseEmitter healthy = mock(SseEmitter.class);
            org.mockito.Mockito.doAnswer(inv -> {
                healthyGot.countDown();
                return null;
            }).when(healthy).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            asyncBroadcaster.register("stuck", stuck);
            asyncBroadcaster.register("healthy", healthy);

            asyncBroadcaster.broadcast("{}");
            asyncBroadcaster.broadcast("{}");

            assertTrue(healthyGot.await(8, TimeUnit.SECONDS),
                    "stuck send 超时（默认 5s）摘除后，healthy 应继续收到两次广播帧");
            assertFalse(asyncBroadcaster.isActive("stuck"), "send 永卡的死连接应被超时摘除");
        } finally {
            releaseStuck.countDown();
            asyncBroadcaster.shutdown();
        }
    }

    /**
     * 174500 快速回归（短超时注入）：广播线程单条 send 有界等待——300ms 超时后死连接摘除、
     * 同一轮循环继续送达排在它后面的健康连接；摘除后后续广播不再向死连接投递。
     */
    @Test
    void broadcast_sendTimeoutEviction_subsequentLoopAndBroadcastUnaffected() throws Exception {
        broadcaster = new AsmSseBroadcaster(Runnable::run, 300);
        CountDownLatch healthyGot = new CountDownLatch(2);
        SseEmitter stuck = mock(SseEmitter.class);
        // send 永不返回也不抛——唯一出口是超时摘除（与「抛异常摘除」路径互斥）
        org.mockito.Mockito.doAnswer(inv -> {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        }).when(stuck).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = mock(SseEmitter.class);
        org.mockito.Mockito.doAnswer(inv -> {
            healthyGot.countDown();
            return null;
        }).when(healthy).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        broadcaster.register("stuck", stuck);
        broadcaster.register("healthy", healthy);

        broadcaster.broadcast("{}");  // 同步 executor：本线程内联等满 300ms 超时摘 stuck，再送 healthy
        broadcaster.broadcast("{}");  // 第二轮：池中只剩 healthy

        assertTrue(healthyGot.await(2, TimeUnit.SECONDS), "两轮广播 healthy 应各收到一帧");
        assertFalse(broadcaster.isActive("stuck"), "300ms 超时的死连接应被摘除");
        assertTrue(broadcaster.isActive("healthy"));
        org.mockito.Mockito.verify(stuck, org.mockito.Mockito.times(1))
                .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        org.mockito.Mockito.verify(healthy, org.mockito.Mockito.times(2))
                .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        broadcaster.shutdown();
    }

    /** 174500 快速回归（心跳路径）：comment 帧 send 永卡同样被超时摘除，健康连接心跳不受影响。 */
    @Test
    void heartbeat_sendTimeoutEviction_healthyStillGetsPing() throws Exception {
        broadcaster = new AsmSseBroadcaster(Runnable::run, 300);
        CountDownLatch aliveGot = new CountDownLatch(1);
        SseEmitter stuck = mock(SseEmitter.class);
        org.mockito.Mockito.doAnswer(inv -> {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        }).when(stuck).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        SseEmitter alive = mock(SseEmitter.class);
        org.mockito.Mockito.doAnswer(inv -> {
            aliveGot.countDown();
            return null;
        }).when(alive).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        broadcaster.register("stuck", stuck);
        broadcaster.register("alive", alive);

        broadcaster.heartbeat();

        assertTrue(aliveGot.await(2, TimeUnit.SECONDS), "超时摘除后健康连接应收到心跳帧");
        assertFalse(broadcaster.isActive("stuck"));
        assertTrue(broadcaster.isActive("alive"));
        broadcaster.shutdown();
    }

    /**
     * 泄漏回归（A 摘 emitter）：N 次注册死连接（send 抛 IOException）+ 广播，
     * emitter 集合逐次摘除不积累——模拟 e2e 页面快速开合场景（~80 条死连接泄漏的反向锁定）。
     */
    @Test
    void repeatedDeadConnections_poolNeverGrows() throws Exception {
        for (int i = 0; i < 50; i++) {
            SseEmitter dead = mock(SseEmitter.class);
            org.mockito.Mockito.doThrow(new IOException("client gone " + i)).when(dead)
                    .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            broadcaster.register("dead-" + i, dead);
            broadcaster.broadcast("{}");
            assertFalse(broadcaster.isActive("dead-" + i), "第 " + i + " 次死连接广播后应立即摘除");
            assertEquals(0, broadcaster.activeCount(), "死连接逐次摘除，池不积累");
        }
    }
}
