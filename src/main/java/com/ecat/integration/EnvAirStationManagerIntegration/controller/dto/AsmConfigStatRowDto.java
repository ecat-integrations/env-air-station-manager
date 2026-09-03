package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * config-stat 列表行 = 配置行字段平铺 + {@code device_label}/{@code attr_label}（前端 ruoyi 化中文
 * 契约，字段名固定不得改）。label 口径与 alarm-record 行同源（{@code AsmDeviceLabelService}）。
 *
 * <p>{@code @JsonUnwrapped} 平铺配置本体：logicDeviceUniqueId/attrId/enabled/... 的 JSON 路径与旧
 * {@code List<AsmConfigStat>} 响应逐字段一致（追加不换口径）。</p>
 *
 * @author coffee
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsmConfigStatRowDto {

    /** 配置行本体（平铺序列化，见类 javadoc）。 */
    @JsonUnwrapped
    private AsmConfigStat stat;

    /** 槽中文名（StationParamMeta label 同源；非站房槽回退 uid 原文）。 */
    @JsonProperty("device_label")
    private String deviceLabel;

    /** attr 中文 displayName（registry attrDefs 同源；解析不到回退 attrId 原文）。 */
    @JsonProperty("attr_label")
    private String attrLabel;

    public static AsmConfigStatRowDto of(AsmConfigStat stat, String deviceLabel, String attrLabel) {
        return AsmConfigStatRowDto.builder()
                .stat(stat)
                .deviceLabel(deviceLabel)
                .attrLabel(attrLabel)
                .build();
    }
}
