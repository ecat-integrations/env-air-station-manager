package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * ASM 自有单位窄投影——不暴露 core {@code UnitInfo} 类型，保持 api 包零 core 依赖。
 *
 * @author coffee
 */
@Value
@Builder
public class SdkUnit {

    /** 单位 full string，机对机唯一传输键（如 {@code PressureUnit.MPA}）。 */
    String fullName;

    /** 单位短符号（如 MPa/°C）。 */
    String symbol;

    /** i18n 显示名。 */
    String displayName;
}
