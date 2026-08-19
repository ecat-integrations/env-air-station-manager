package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * snapshot 单属性行——live 实时态优先，raw 最新值回放兜（live state null/无值时）。
 *
 * @author coffee
 */
@Value
@Builder
public class AsmSnapshotAttrDto {

    /** logic attr id。 */
    String attrId;

    /** 数值业务值（已按 MONITOR 读出口换算；非数值/text 属性为 null）。 */
    Double value;

    /** 非数值展示串（开关/文本态属性；数值属性为 null）。 */
    String valueText;

    /** value 实际单位（full key；null=无量纲）。 */
    String unit;

    /** 该值时刻（LIVE=state.lastUpdated / RAW=样本 data_time）。 */
    Instant updateTime;

    /** 值来源：LIVE（总线实时态）/ RAW（asm_data_sample 最新值回放）。 */
    String source;
}
