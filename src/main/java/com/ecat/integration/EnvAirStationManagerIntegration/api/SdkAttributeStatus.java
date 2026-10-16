package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * ASM 自有属性状态窄投影——不暴露 core {@code AttributeStatus} 类型，保持 api 包零 core 依赖。
 *
 * @author coffee
 */
@Value
@Builder
public class SdkAttributeStatus {

    /** 稳定状态 key（LD AttributeStatus 枚举名，如 NORMAL/ALARM）。 */
    String key;

    /** 状态 i18n 显示名（如「零点检查」）。 */
    String displayName;
}
