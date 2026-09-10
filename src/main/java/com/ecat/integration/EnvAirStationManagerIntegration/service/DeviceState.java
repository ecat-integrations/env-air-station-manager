package com.ecat.integration.EnvAirStationManagerIntegration.service;

/**
 * 类型槽三态（纯读 registry，与 ADM DeviceState 同构）：
 * CONFIGURED（active logic device 且有活物理设备）/ UNBOUND（logic device 或 entry 存在但无活物理设备）
 * / NOT_CREATED（均不存在）。
 *
 * @author coffee
 */
public enum DeviceState {
    CONFIGURED,
    UNBOUND,
    NOT_CREATED
}
