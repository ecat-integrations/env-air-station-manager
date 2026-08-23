package com.ecat.integration.EnvAirStationManagerIntegration.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * config flow 单字段（前端 lit {@code <flow-form>} 字段渲染契约：{@code field.key/displayName/fieldType/
 * defaultValue/required/readOnly/description/placeholder/options}，由 lib FieldRegistry 按 fieldType 分派渲染器）。
 *
 * <p>{@code fieldType} 透传后端 {@link com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem#getFieldType()}
 * 原值（"string"/"number"/"select"/"dynamic_enum"…）——lib 已注册 "select"/"dynamic_enum"→EnumFieldRenderer 别名、
 * 其余未知类型 fallback 到 TextFieldRenderer，故无需在此层做类型映射。displayName 是字面中文（comm schema 直配）。
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowFieldDto {
    /** 字段键（提交 userInput 的 key）。 */
    private String key;
    /** 字段展示名（label）。 */
    private String displayName;
    /** 字段类型（透传后端原值，lib 映射）。 */
    private String fieldType;
    /** 默认值。 */
    private Object defaultValue;
    /** 是否必填。 */
    private boolean required;
    /** 是否只读。 */
    private boolean readOnly;
    /** 字段描述（hint）。 */
    private String description;
    /** 占位提示。 */
    private String placeholder;
    /** 选项列表（enum/array 类型）；非枚举字段为 null。 */
    private List<FlowOptionDto> options;

    /**
     * 嵌套子字段（仅 fieldType="schema" 非空）：SchemaConfigItem 包裹的子 ConfigSchema 展平成字段列表，
     * 如 thermofisher 1405f 的 serial_settings 嵌套 SerialCommConfigSchema（serial_port/baudrate/data_bits…）。
     * 前端 SchemaFieldRenderer 读 field.nestedFields 递归渲染子表单，getValue 收集成嵌套对象提交
     *（{serial_settings:{serial_port,…}}），与后端 SchemaConfigItem 期望的 Map 契约一致。
     * 对齐 SPA 的 SchemaConversionService.extractSchemaFields。
     */
    private List<FlowFieldDto> nestedFields;
}
