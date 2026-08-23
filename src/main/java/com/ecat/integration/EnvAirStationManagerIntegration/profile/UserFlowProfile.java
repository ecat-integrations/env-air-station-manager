package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import java.util.Map;

/**
 * USER_FLOW 策略 profile（与 ADM 同构）。
 *
 * <p>ASM 作为编排器对厂家 flow 做数据 hydrate：把 ASM 已知的合理默认值（分类 class/model、协议
 * device_protocol 等）预填进 flow 对应步。{@link #getIdentityInputs()} 即 hydrate 默认值映射
 * {@code Map<stepId, Map<field, value>>}。sn/name 属用户专属，不入此 Map——driver 见 schema
 * required 但 profile 未给的字段即停步交 flow-form 让用户补全。
 */
public interface UserFlowProfile extends StationProvisionProfile {

    @Override
    default ProvisionStrategy getStrategy() {
        return ProvisionStrategy.USER_FLOW;
    }

    /** hydrate 默认值映射（stepId → fieldKey → 具体默认值）。 */
    Map<String, Map<String, String>> getIdentityInputs();
}
