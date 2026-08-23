package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 通用 USER_FLOW profile（坐标×型号参数化实例）。
 *
 * <p>站房矩阵 48 个 (类型, 厂商, 型号) 组合走同一 USER flow 机制，仅 coordinate/model/identityInputs
 * 不同，故用单值类 + {@code StationProfileRegistrar} 集中注册（区别 ADM 逐型号独立类——ASM 侧
 * 各集成身份步模板尚未逐型号精化，identityInputs 先空 Map：driver 自动跳过无 required 的欢迎/说明步、
 * 停在首个含 required 字段的身份/连接步交用户填写；逐型号补 hydrate 默认值时在此填 Map 即可）。
 */
@Value
@Builder
public class SimpleUserFlowProfile implements UserFlowProfile {

    StationParamMeta stationParam;
    String coordinate;
    String model;
    /** hydrate 默认值（可为空 Map：无已知默认即全交用户）。 */
    Map<String, Map<String, String>> identityInputs;
}
