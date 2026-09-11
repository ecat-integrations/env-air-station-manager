package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 控制审计行（{@code asm_control_record}，设计 §7）——ASM 控制收口的唯一审计真相源：
 * 先落 PENDING（before/requested 已知），执行完成后回填 result/after_value/duration_ms。
 * before 为 AttrState 快照串（displayValue[ unit]）；after 为下发设置值留痕串（值[ 单位 full string]）。
 *
 * @author coffee
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsmControlRecord {

    private Long id;

    /** 调用来源（REST=REMOTE / SDK=LOCAL）。 */
    private AsmControlOrigin origin;

    /** REMOTE=认证 principal；LOCAL=消费方集成坐标。 */
    private String caller;

    private String logicDeviceUniqueId;

    private String attrId;

    /** WRITE|COMMAND（按 attr 类型派生）。 */
    private String action;

    /** 调用前 AttrState 快照（displayValue[ unit]）。 */
    private String beforeValue;

    private String requestedValue;

    /**
     * 下发设置值留痕（与 requested_value 同源同形，值[ 单位 full string]）——
     * SUCCESS/FAILED/TIMEOUT 三态统一记录（审计成败皆留痕，是否生效由 result 表达；
     * 不回读执行后镜像态，logic 镜像异步刷新存在竞态，bug-record-20260911-104245）。
     */
    private String afterValue;

    private AsmControlResult result;

    private String error;

    /** 执行耗时（execute 起算到终态回填；ms）。 */
    private Long durationMs;

    private Instant createdAt;
}
