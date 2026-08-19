package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * 控制调用来源（审计维度，设计 §7）：
 * REMOTE=REST /asm-monitor/control（caller=认证 principal）；
 * LOCAL=进程内 SDK {@code control()}（caller=消费方集成坐标）。
 *
 * @author coffee
 */
public enum AsmControlOrigin {
    LOCAL,
    REMOTE
}
