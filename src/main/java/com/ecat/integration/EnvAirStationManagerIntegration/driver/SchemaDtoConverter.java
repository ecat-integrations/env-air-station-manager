package com.ecat.integration.EnvAirStationManagerIntegration.driver;

import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.DynamicEnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.SchemaConfigItem;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.dto.FlowFieldDto;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.dto.FlowOptionDto;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.dto.FlowSchemaDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ConfigSchema} → {@link FlowSchemaDto} 转换器（自包含于本集成，不依赖 ecat-core-api 的 SchemaConversionService——
 * 避免业务集成跨 plugin 依赖 api 型集成；只做"剥不可序列化字段 + 取干净字段 + fieldType 映射"的最小转换）。
 *
 * <p><b>fieldType 映射</b>：后端 ConfigItem 词汇表（string/number/integer/select/dynamic_enum…）与 lit lib renderer
 * 词汇表（text/numeric/short/enum…）不同；lib 只为 select/dynamic_enum 注册了 enum 别名，其余（string/number/integer）
 * 会 fallback 到 text 并打 Unknown fieldType 警告。本转换器把后端 fieldType 映射到 lib renderer 键，消除告警 + 让数字字段
 * 用 numeric 渲染器（与 core-api 的 SchemaConversionService 给 SPA 做的映射同源）。
 *
 * <p>严格模式：schema 为 null 返回 null（不猜测构造空 schema）；字段列表逐项映射，enum/dynamic_enum 选项从各自访问器取。
 *
 * @author coffee
 */
public final class SchemaDtoConverter {

    /** 后端 ConfigItem.fieldType → lit lib renderer fieldType（未知类型原样透传，lib 自行 fallback）。 */
    private static final Map<String, String> FIELD_TYPE_MAP;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("string", "text");
        m.put("number", "numeric");
        m.put("integer", "short");
        m.put("select", "enum");
        m.put("dynamic_enum", "enum");
        FIELD_TYPE_MAP = Collections.unmodifiableMap(m);
    }

    private SchemaDtoConverter() {
    }

    /** 后端 fieldType → lit lib renderer fieldType（map 命中则转，否则原样透传）。 */
    private static String mapFieldType(String backendFieldType) {
        if (backendFieldType == null) {
            return null;
        }
        return FIELD_TYPE_MAP.getOrDefault(backendFieldType, backendFieldType);
    }

    /**
     * 转换 ConfigSchema → 前端可序列化的 FlowSchemaDto（剥 i18nProxy/i18nKeyPrefix）。
     *
     * @param schema 后端原始 schema（可 null）
     * @return 干净 DTO；schema 为 null 返回 null
     */
    public static FlowSchemaDto convert(ConfigSchema schema) {
        if (schema == null) {
            return null;
        }
        // ConfigSchema 无 title/description 字段；flow-form 无 title 默认 "配置"
        return new FlowSchemaDto(null, null, convertFields(schema.getFields()));
    }

    /** 字段列表 → DTO 列表（顶层 schema 与嵌套子 schema 复用同一递归入口）。 */
    private static List<FlowFieldDto> convertFields(List<AbstractConfigItem<?>> raw) {
        List<FlowFieldDto> fields = new ArrayList<>();
        if (raw != null) {
            for (AbstractConfigItem<?> f : raw) {
                fields.add(convertField(f));
            }
        }
        return fields;
    }

    /**
     * 单字段 → DTO。SchemaConfigItem（fieldType="schema"）递归其 resolveSchema() 把子字段展平进
     * nestedFields；fieldType="schema" 原样透传（FIELD_TYPE_MAP 无此键），前端 SchemaFieldRenderer 接住。
     */
    private static FlowFieldDto convertField(AbstractConfigItem<?> f) {
        List<FlowFieldDto> nested = null;
        if (f instanceof SchemaConfigItem) {
            ConfigSchema nestedSchema = ((SchemaConfigItem) f).resolveSchema();
            if (nestedSchema != null) {
                nested = convertFields(nestedSchema.getFields());
            }
        }
        return new FlowFieldDto(
                f.getKey(),
                f.getDisplayName(),
                mapFieldType(f.getFieldType()),
                f.getDefaultValue(),
                f.isRequired(),
                f.isReadOnly(),
                f.getDescription(),
                f.getPlaceholder(),
                optionsOf(f),
                nested);
    }

    /**
     * 取枚举类字段选项（EnumConfigItem.getOptionLabels / DynamicEnumConfigItem.getOptions 均 Map&lt;value,label&gt;）。
     * 非枚举字段返回 null（lib 做 {@code field.options || []} 兜底）。
     */
    private static List<FlowOptionDto> optionsOf(AbstractConfigItem<?> f) {
        Map<String, String> labels = null;
        if (f instanceof EnumConfigItem) {
            labels = ((EnumConfigItem) f).getOptionLabels();
        } else if (f instanceof DynamicEnumConfigItem) {
            labels = ((DynamicEnumConfigItem) f).getOptions();
        }
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        List<FlowOptionDto> opts = new ArrayList<>(labels.size());
        for (Map.Entry<String, String> e : labels.entrySet()) {
            opts.add(new FlowOptionDto(e.getKey(), e.getValue()));
        }
        return opts;
    }
}
