package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import java.util.Map;

/**
 * 站房物理设备 provision profile（1:1 配套 logicdevice-airstation 的 DeviceMappings，仿 ADM）。
 *
 * <p>三级结构：{@code 站房设备类型 → integration(coordinate) → model → profile}。
 * 每个 profile 描述「某类型槽 × 某厂家型号」如何 provision 物理设备。profile 自声明
 * {@link #getStrategy()}，{@code FlowDriver} 据此分派 IMPORT_FLOW / USER_FLOW。
 *
 * <p>与 ADM 差异：无 aggregationMode（ASM 物化模式与设备型号无关，绑定不联动配置表）。
 */
public interface StationProvisionProfile {

    /** provision 机制（IMPORT_FLOW / USER_FLOW），FlowDriver 据此分派。 */
    ProvisionStrategy getStrategy();

    /** 所属站房设备类型槽（分组键）。 */
    StationParamMeta getStationParam();

    /** 物理集成 coordinate（groupId:artifactId）。 */
    String getCoordinate();

    /** 物理设备型号。 */
    String getModel();

    /**
     * 算该设备 uniqueId（多引用设备解绑后重绑→自动复用已存在设备）。
     * 委托 {@link AsmUniqueIdFormulas}——uniqueId 公式唯一集中地。字段从 entryData 取
     * （与子集成 generateUniqueId 同源）；字段未齐返回 null（每 step 监测自然跳过）。
     */
    default String buildUniqueId(Map<String, Object> entryData) {
        return AsmUniqueIdFormulas.build(getCoordinate(), entryData);
    }
}
