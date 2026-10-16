package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * caller-defined snapshot 行——LD 原值 → 显式目标单位 → 请求精度 HALF_EVEN 的不可变投影。
 *
 * <p>数值行 {@code value/valueText/displayPrecision} 非 null，带量纲数值行 {@code unit}
 * 非 null 而 dimensionless 数值行为 null；展示型非数值 LIVE 行只有 {@code valueText}；
 * DEF 行只保留可解释的请求投影元数据，值与观测态为 null。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkCustomSnapshotAttr {

    /** attr id。 */
    String attrId;

    /** 数值最终值；非数值或 DEF 行 null。 */
    Double value;

    /**
     * LIVE 统一展示串：数值=请求精度固定小数（一次 HALF_EVEN 后 {@code toPlainString()}）；
     * 非数值=LD {@code state.displayValue}；DEF=null。
     */
    String valueText;

    /** 最终单位对象；带量纲数值行非空，dimensionless 数值与非数值行 null。 */
    SdkUnit unit;

    /** 实际应用精度；数值行非 null，非数值行 null。 */
    Integer displayPrecision;

    /** LD 主状态投影；DEF 行 null。 */
    SdkAttributeStatus status;

    /** LD {@code state.lastUpdated}；DEF 行 null。 */
    Instant updateTime;

    /** LIVE=LD state 有值 / DEF=attr 已定义但无值。 */
    String source;
}
