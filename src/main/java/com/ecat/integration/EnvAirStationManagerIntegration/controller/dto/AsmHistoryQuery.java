package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * 历史查询请求 DTO（{@code GET /asm-monitor/history} 参数集）。
 *
 * <p>壁钟→绝对时刻换算在 controller（JVM_ZONE），本 DTO 携带的是 UTC Instant。
 * mode/unit/pageNum/pageSize 缺省由 {@code @Builder.Default} 兜（BACK/custom/1/50），
 * service 仍做合法性校验（严格模式）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class AsmHistoryQuery {

    /** 粒度枚举名（MINUTE/FIVE_MIN/HOUR）。 */
    String granularity;

    /** 窗口起（含，UTC Instant）。 */
    Instant start;

    /** 窗口止（含，UTC Instant）。 */
    Instant end;

    /** 选中参数键列表（可空 → 空结果）。 */
    List<AsmHistoryParamKey> params;

    /** 区间模式查看视角（FRONT/BACK；缺省 BACK=国标后闭）。 */
    String mode;

    /** 单位模式（standard=恒原生 / custom=应用 HISTORY 偏好；缺省 custom）。 */
    String unit;

    /** 页码（缺省 1）。 */
    Integer pageNum;

    /** 每页桶数（缺省 50）。 */
    Integer pageSize;
}
