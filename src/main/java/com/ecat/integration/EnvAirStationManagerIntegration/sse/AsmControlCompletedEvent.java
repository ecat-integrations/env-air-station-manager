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
     * 终态时刻的权威显示值快照（displayValue + 单位，同审计 after_value）。
     * core attr 语义保证：SUCCESS 终态时 attr 可读状态必为新值（数值型乐观更新 AttributeBase / Command 型 ACK 后同步更新），
     * 故该快照即权威值，前端「帧到即收敛」免回跳。TIMEOUT 不猜结果（既有设计），恒 null。
     */
    String afterValue;
}
