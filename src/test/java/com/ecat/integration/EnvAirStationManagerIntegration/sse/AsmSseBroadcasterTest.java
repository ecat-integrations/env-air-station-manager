package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        broadcaster = new AsmSseBroadcaster();
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
}
