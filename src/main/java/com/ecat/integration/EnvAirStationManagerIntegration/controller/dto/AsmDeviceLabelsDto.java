package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * alarm-rule/list 行 {@code deviceLabels} 元素——单槽中文标注（前端 ruoyi 化契约，字段名固定不得改）。
 * 解析收口在 {@code AsmDeviceLabelService}（slot 与 StationParamMeta label 同源 / attr 与 registry
 * attrDefs displayName 同源，解析不到如实回退原文）。
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsmDeviceLabelsDto {

    /** 槽中文名（StationParamMeta label 同源，多实例槽各带实例名如「CO标气」；非站房槽回退 uid 原文）。 */
    private String slot;

    /** 该槽涉及 attr 的中文 displayName（与 device_info attr 顺序一致；设备不在 registry/无 def 回退 attrId 原文）。 */
    private List<String> attrs;
}
