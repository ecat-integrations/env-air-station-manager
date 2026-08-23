package com.ecat.integration.EnvAirStationManagerIntegration.driver;

/**
 * 操作类型：添加 / 更换 / 改连接。移除不走 flow（unbind 直清绑定）。
 *
 * <p>{@code RECONFIGURE} = 改连接（对已绑物理设备启动 RECONFIGURE flow 改连接参数，设备没换、uniqueId/逻辑绑定不变）。
 * reconfigureConnection 启动 flow 时注册 RECONFIGURE 上下文，让 submitFlowStep 在 CREATE_ENTRY 终态按 operation
 * 分流到 fireReconfigureAtCompletion（与其他 4 收口点统一「完成才记」，非启动即记）。
 */
public enum Operation {
    ADD,
    REPLACE,
    RECONFIGURE
}
