package com.ecat.integration.EnvAirStationManagerIntegration.profile;

/**
 * 物理设备 provision 机制（与 ADM 双策略同构：架构灵活适配两种集成 flow 机制）。
 *
 * <ul>
 *   <li>{@link #IMPORT_FLOW}：集成已实现 IMPORT_FLOW discovery handler（如 saimosen）。
 *       profile 构造 import data（{@code class|model|sn|name} 等），driver 调
 *       {@code startDiscoveryFlow(IMPORT_FLOW)}，集成自校验+预填+直达连接步。</li>
 *   <li>{@link #USER_FLOW}：集成仅/优先走 USER config flow（无 IMPORT_FLOW handler）。
 *       driver 启动 USER flow，按 profile 身份步模板快进身份步、自动跳过欢迎/说明步、停连接步。</li>
 * </ul>
 */
public enum ProvisionStrategy {
    IMPORT_FLOW,
    USER_FLOW
}
