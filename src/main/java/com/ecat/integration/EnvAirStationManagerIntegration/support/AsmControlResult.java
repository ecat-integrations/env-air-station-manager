package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * 控制执行结果：先落 PENDING，异步完成后回填终态；
 * 超时如实记 {@link #TIMEOUT}（不猜结果），迟到执行不覆盖已回填的终态。
 *
 * @author coffee
 */
public enum AsmControlResult {
    PENDING,
    SUCCESS,
    FAILED,
    TIMEOUT
}
