package com.ecat.integration.EnvAirStationManagerIntegration.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * config flow 步骤表单 schema（前端 lit {@code <flow-form>} 的 {@code :schema} 属性契约：{@code {title,description,fields}}）。
 *
 * <p>由 {@link com.ecat.integration.EnvAirStationManagerIntegration.driver.SchemaDtoConverter} 从
 * {@link com.ecat.core.ConfigFlow.ConfigSchema} 转换——剥离 ConfigSchema 的 i18nProxy（不可序列化）
 * 与 i18nKeyPrefix，只保留前端渲染所需的干净字段列表。{@code stepId} 不在此 DTO 内（flow-form 把 stepId 作为
 * 独立 prop，由 ProvisionResult.stoppedStepId / SubmitResult.stepId 单独传）。
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowSchemaDto {
    /** 步骤标题（flow-form 无则默认 "配置"；ConfigSchema 本身无标题字段，暂 null）。 */
    private String title;
    /** 步骤描述。 */
    private String description;
    /** 字段定义列表。 */
    private List<FlowFieldDto> fields;
}
