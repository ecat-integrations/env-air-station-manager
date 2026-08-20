package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AsmMonitorSseController 单测——token query 校验（缺失/无效/裸 token Bearer 转发）+ emitter 注册。
 * 平移 ADM AdmMonitorSseControllerTest 模式（TokenService 非 final 可 mock；无 sleep）。
 */
class AsmMonitorSseControllerTest {

    private AsmSseBroadcaster broadcaster;
    private TokenService tokenService;
    private AsmMonitorSseController controller;

    @BeforeEach
    void setUp() {
        broadcaster = mock(AsmSseBroadcaster.class);
        tokenService = mock(TokenService.class);
        controller = new AsmMonitorSseController(broadcaster, tokenService);
    }

    @Test
    void stream_validToken_returnsEmitter_andRegisters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("admin");
        when(tokenService.getLoginUser(any(HttpServletRequest.class))).thenReturn(loginUser);

        SseEmitter emitter = controller.stream("valid-jwt", request);

        assertNotNull(emitter, "有效 token 应返回 SseEmitter");
        verify(broadcaster, times(1)).register(any(String.class), any(SseEmitter.class));
    }

    @Test
    void stream_tokenMissing_throwsUnauthorized() {
        assertThrows(AsmMonitorSseController.UnauthorizedException.class,
                () -> controller.stream(null, new MockHttpServletRequest()));
    }

    @Test
    void stream_emptyToken_throwsUnauthorized() {
        assertThrows(AsmMonitorSseController.UnauthorizedException.class,
                () -> controller.stream("", new MockHttpServletRequest()));
    }

    @Test
    void stream_tokenInvalid_tokenServiceReturnsNull_throwsUnauthorized() {
        when(tokenService.getLoginUser(any(HttpServletRequest.class))).thenReturn(null);
        assertThrows(AsmMonitorSseController.UnauthorizedException.class,
                () -> controller.stream("forged", new MockHttpServletRequest()));
    }

    /** 裸 token（无 Bearer 前缀）应补前缀后注入 Authorization header 转发给 TokenService。 */
    @Test
    void stream_bareToken_prependedBearerAndForwarded() {
        String bare = "my-jwt-123";
        MockHttpServletRequest request = new MockHttpServletRequest();
        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("admin");

        org.mockito.ArgumentCaptor<HttpServletRequest> captor =
                org.mockito.ArgumentCaptor.forClass(HttpServletRequest.class);
        when(tokenService.getLoginUser(captor.capture())).thenReturn(loginUser);

        controller.stream(bare, request);

        assertEquals("Bearer " + bare, captor.getValue().getHeader("Authorization"),
                "裸 token 应补 Bearer 前缀注入 Authorization header");
    }

    /**
     * 心跳 daemon task 必 catch 回归：scheduleAtFixedRate 的 task 抛未捕获异常会被永久抑制
     * （心跳死亡）。heartbeatTick 是 task 体唯一入口——其依赖（broadcaster.heartbeat）抛
     * RuntimeException 时 tick 不得外抛，保调度连续（与 AsmStatRefreshScheduler.scheduledTick 同纪律）。
     */
    @Test
    void heartbeatTick_broadcasterThrows_doesNotPropagate_toKeepScheduledTaskAlive() {
        org.mockito.Mockito.doThrow(new RuntimeException("poison emitter escaped"))
                .when(broadcaster).heartbeat();

        controller.heartbeatTick(); // 不抛即「必 catch 保调度连续」

        verify(broadcaster, times(1)).heartbeat();
    }
}
