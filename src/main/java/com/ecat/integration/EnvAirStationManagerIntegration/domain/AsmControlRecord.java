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
 * before/after 为 AttrState 快照串（displayValue + unit）。
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

    /** 执行完成后回读的新 AttrState 快照；TIMEOUT 不猜结果（null）。 */
    private String afterValue;

    private AsmControlResult result;

    private String error;

    /** 执行耗时（execute 起算到终态回填；ms）。 */
    private Long durationMs;

    private Instant createdAt;
}
