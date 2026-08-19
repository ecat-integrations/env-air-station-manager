package com.ecat.integration.EnvAirStationManagerIntegration.service;

import lombok.Builder;
import lombok.Value;

/**
 * 读出口单位换算结果——值 + 实际单位 + 是否发生换算（不可变）。
 *
 * <p>{@code unit} 恒为 {@code value} 的实际单位（getFullUnitString key 形式）：换算可达=目标单位；
 * 缺行/换算不可达/直通=源（原生）单位——「显原生」是设计语义非兜底（D4）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class AsmDisplayValue {

    /** 换算后的数值；入参 null 时为 null。 */
    Double value;

    /** value 的实际单位（full key 形式）；源无单位时为 null。 */
    String unit;

    /** 是否实际发生单位换算（false=直通/显原生/换算不可达降级）。 */
    boolean converted;

    /** 便捷工厂。 */
    public static AsmDisplayValue of(Double value, String unit, boolean converted) {
        return AsmDisplayValue.builder().value(value).unit(unit).converted(converted).build();
    }
}
