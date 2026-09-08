package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkParamMeta;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * stat-params 列表行 = SDK 参数元数据平铺 + {@code display_unit}/{@code device_label}（历史数据页
 * 中文契约，字段名固定不得改）。label 口径与 config-stat/alarm-record 行同源
 * （{@code AsmDeviceLabelService}）；display_unit 源=core {@code UnitInfo.getDisplayName()}。
 *
 * <p>{@code @JsonUnwrapped} 平铺 SDK meta 本体：logicDeviceUniqueId/attrId/paramDisplayName/
 * storageUnit/applicableGranularityMask 的 JSON 路径与旧 {@code List<SdkParamMeta>} 响应逐字段一致
 * （追加不换口径；SDK 机对机契约本体不加中文展示字段）。</p>
 *
 * @author coffee
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsmStatParamRowDto {

    /** SDK 参数元数据本体（平铺序列化，见类 javadoc）。 */
    @JsonUnwrapped
    private SdkParamMeta meta;

    /** 单位人类显示串（storageUnit full key → UnitInfo.getDisplayName；解析失败回退原文；null=无量纲）。 */
    @JsonProperty("display_unit")
    private String displayUnit;

    /** 槽中文名（StationParamMeta label 同源；非站房槽回退 uid 原文）。 */
    @JsonProperty("device_label")
    private String deviceLabel;

    public static AsmStatParamRowDto of(SdkParamMeta meta, String displayUnit, String deviceLabel) {
        return AsmStatParamRowDto.builder()
                .meta(meta)
                .displayUnit(displayUnit)
                .deviceLabel(deviceLabel)
                .build();
    }
}
