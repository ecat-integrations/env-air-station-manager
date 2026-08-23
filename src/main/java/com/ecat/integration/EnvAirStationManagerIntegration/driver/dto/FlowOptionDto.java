package com.ecat.integration.EnvAirStationManagerIntegration.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 枚举/多选字段选项（前端 lit {@code <flow-form>} 的 enum-field 读 {@code field.options} 数组，
 * 元素 {value,label}）。由 {@link com.ecat.integration.EnvAirStationManagerIntegration.driver.SchemaDtoConverter}
 * 从 {@link com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem#getOptionLabels()} /
 * {@link com.ecat.core.ConfigFlow.ConfigItem.DynamicEnumConfigItem#getOptions()} 的 Map&lt;value,label&gt; 转换而来。
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowOptionDto {
    /** 选项值（提交时回传给后端的 key）。 */
    private String value;
    /** 选项展示文本。 */
    private String label;
}
