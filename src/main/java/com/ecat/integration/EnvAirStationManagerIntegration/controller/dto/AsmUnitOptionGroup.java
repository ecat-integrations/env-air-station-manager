package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Value;

import java.util.List;

/**
 * snapshot 数值行单位候选组（单位设置抽屉数据源）：同类兄弟单位一组 +（气态源）跨类浓度组，按类分组。
 *
 * <p>结构：{@code [{classLabel:"质量浓度", units:[{key:"AirMassUnit.MGM3", symbol:"mg/m3"}, ...]}]}。
 * 同类组恒在最前；跨类组供用户选择 mg/m³↔ppm 等唯一可跨类换算域——跨类目标选中后换算走既有语义
 * （站房域非气态无可换算对，显原生+warn），不在候选层做过滤。</p>
 *
 * @author coffee
 */
@Value
public class AsmUnitOptionGroup {

    /** 类中文标签（如「质量浓度」「温度」）。 */
    String classLabel;

    /** 该类全部单位（full key + 符号），枚举声明序。 */
    List<UnitOption> units;

    /** 单个单位候选。 */
    @Value
    public static class UnitOption {
        /** UnitInfo.getFullUnitString key（PUT config-unit unit 字段直接回传）。 */
        String key;
        /** 单位符号（如 mg/m3、°C）。 */
        String symbol;
    }
}
