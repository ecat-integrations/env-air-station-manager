package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Getter;

/**
 * SDK 控制结果行（{@code AirStationSdk#control} 出参）——控制审计行面向外部消费方的稳定投影：
 * recordId 可回查 {@code asm_control_record}；result 取值 PENDING/SUCCESS/FAILED/TIMEOUT
 * （异步执行同步返回 PENDING，终态经 recordId 侧通道/重发确认）。
 *
 * @author coffee
 */
@Getter
@Builder
public class SdkControlResult {

    /** 审计记录 id（asm_control_record 主键）。 */
    private final Long recordId;

    /** 回显请求 origin（LOCAL=本站/集成自身发起；REMOTE=第三方代传远程侧指令）。 */
    private final String origin;

    /** PENDING|SUCCESS|FAILED|TIMEOUT。 */
    private final String result;

    private final String error;

    /** 执行耗时（ms；PENDING 时 null）。 */
    private final Long durationMs;
}
