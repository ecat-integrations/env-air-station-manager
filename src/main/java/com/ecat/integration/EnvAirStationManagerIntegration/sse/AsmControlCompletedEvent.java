package com.ecat.integration.EnvAirStationManagerIntegration.sse;

import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import lombok.Value;

/**
 * 控制终态 SSE 具名帧载荷——{@code AsmControlService.finalizeOutcome} 落终态后
 * 经 {@code AsmSseBroadcaster#broadcastNamed} 推送，前端 device_control 页按 id 匹配在途项落终态徽章
 * （终态唯一来源，无轮询）。uid/attrId 供多页/日志定位；result 终态枚举；error 仅 FAILED 非 null。
 *
 * <p>帧名 {@link #TYPE}（"control.completed"）；uid 前缀校验在提交入口（非站房不受理），
 * 故本帧天然站房-only（AsmControlService 受理时校验 uid 前缀）。</p>
 *
 * @author coffee
 */
@Value
public class AsmControlCompletedEvent {

    /** SSE 具名帧名（前端 addEventListener/onmessage 按 event 帧头匹配）。 */
    public static final String TYPE = "control.completed";

    /** 审计记录 id（前端提交时已持有，按 id 匹配在途项）。 */
    long id;

    /** 站房逻辑设备 uniqueId（logicdevice_station.*）。 */
    String uid;

    /** logic attr id。 */
    String attrId;

    /** 执行终态（SUCCESS / FAILED / TIMEOUT）。 */
    AsmControlResult result;

    /** 失败原因（仅 FAILED 非 null；成功/超时为 null）。 */
    String error;

    /**
     * 下发设置值留痕（值[ 单位 full string]，同审计 after_value，与 requested_value 同源同形）。
     * SUCCESS/FAILED/TIMEOUT 三态统一记录（审计口径：成败皆留痕，是否生效由 result 表达）——
     * 不读回执行后镜像态，规避 logic 镜像异步刷新竞态，前端「帧到即收敛」免回跳。
     */
    String afterValue;
}
