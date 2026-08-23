package com.ecat.integration.EnvAirStationManagerIntegration.driver;

import com.ecat.integration.EnvAirStationManagerIntegration.driver.dto.FlowSchemaDto;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * FlowDriver.provision 输出。
 *
 * <p>{@code flowId} + {@code status}(NEED_USER_INPUT=停连接步交前端 / COMPLETED=IMPORT_FLOW 或 USER_FLOW 直接 CREATE_ENTRY)
 * + {@code stoppedStepId} + {@code schema}（前端 Lit {@code <flow-form>} 渲染连接步用，已转 {@link FlowSchemaDto} 剥 i18nProxy）
 * + {@code stepInputs}（flow-form :data 水化用，回退回显已填）+ {@code savedEntryId}（COMPLETED 时新物理 entryId，供 service 原子收口）。
 * IMPORT_FLOW ABORT 不走本结构（driver 直接抛异常报 reason）。
 *
 * @author coffee
 */
@Value
@Builder
public class ProvisionResult {
    /** driver 状态：停连接步交前端 / IMPORT_FLOW 或 USER_FLOW 已 CREATE_ENTRY。 */
    public enum Status { NEED_USER_INPUT, COMPLETED }

    String flowId;
    Status status;
    /** provision 停下的 stepId（连接步 / CREATE_ENTRY 步）。 */
    String stoppedStepId;
    /** 连接步 schema（前端渲染，已转 DTO 剥 i18nProxy）；COMPLETED 时为 null。 */
    FlowSchemaDto schema;
    /** 已收集的步骤输入（flow-form :data 水化，回退回显）。 */
    Map<String, Object> stepInputs;
    /** COMPLETED 时新物理设备 entryId（service 原子收口用）；NEED_USER_INPUT 时为 null。 */
    String savedEntryId;
}
