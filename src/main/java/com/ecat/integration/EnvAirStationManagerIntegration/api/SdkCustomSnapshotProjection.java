package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * 单个 attr 的 caller-defined snapshot 投影契约。
 *
 * <p>{@code targetUnitFullName} 区分「漏传」与「明确不换算」：null 是调用方契约错误；
 * 空串是显式直通用源单位；非空只能是单位 full string。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkCustomSnapshotProjection {

    /** attr id。 */
    String attrId;

    /**
     * 目标单位 full string（如 {@code PressureUnit.MPA}）：null 非法；空串=明确不换算；
     * 非空仅数值属性合法，禁止传展示符号（如 MPa）。
     */
    String targetUnitFullName;

    /** 数值属性目标小数位：非 null 且 0..6；展示型非数值属性必须为 null。 */
    Integer displayPrecision;
}
