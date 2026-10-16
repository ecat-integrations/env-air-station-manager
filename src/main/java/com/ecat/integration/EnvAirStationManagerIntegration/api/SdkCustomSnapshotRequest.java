package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * SDK caller-defined snapshot 请求——逐 attr 显式声明目标单位与精度，不读取 ASM 展示偏好。
 *
 * <p>不可变 DTO（对外稳定契约包成员）。{@code uid/attrId} 线上承载类型为 String；调用方构造时
 * 须使用 LD 常量，目标单位须使用单位枚举 {@code getFullUnitString()}。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkCustomSnapshotRequest {

    /** 站房逻辑设备 uniqueId（{@code logicdevice_station.*}）。 */
    String uid;

    /** 调用方要投影的属性清单；非空、projection 非 null、attrId 不可重复。 */
    List<SdkCustomSnapshotProjection> projections;
}
