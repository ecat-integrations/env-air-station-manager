package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * 历史查询结果——扁平行集（每行一个 series 一个桶，含换算后值 + 实际单位），前端按 uid:attrId 分组渲染。
 *
 * <p>与 ADM 横表（columns+cells pivot）不同：ASM avg-only 无 statuses 列，扁平行集足够且免 pivot
 * last-write-wins 串台面；分页按桶行（LIMIT/OFFSET，窗口上限已由 service 卡死无深 OFFSET）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class AsmHistoryResult {

    /** 粒度枚举名（回显）。 */
    String granularity;

    /** 区间模式（回显）。 */
    String mode;

    /** 单位模式（回显）。 */
    String unit;

    /** 页码。 */
    int pageNum;

    /** 每页桶数。 */
    int pageSize;

    /** 窗口内桶行总数（count 与行集同 WHERE，前端据此做真分页页数/总数展示）。 */
    long total;

    /** 行集（dataTime 升序）。 */
    List<Row> rows;

    /** 一行：一个 series 在一个桶上的换算后值。 */
    @Value
    @Builder
    public static class Row {

        /** 桶标注时刻（UTC）。 */
        Instant dataTime;

        /** 站房逻辑设备 uniqueId。 */
        String logicDeviceUniqueId;

        /** logic attr id。 */
        String attrId;

        /** 桶均值（已按 unit 模式换算/直通）。 */
        Double value;

        /** 非数值统计值（ALARM: normal/alarm；STATE: 状态串），原样透传不换算；数值行为 null。 */
        @JsonProperty("value_text")
        String valueText;

        /** value 实际单位（full key；null=无量纲）。 */
        String unit;

        /** 单位人类显示串（unit full key → UnitInfo.getDisplayName；换算出口实际单位同源转换；null=无量纲）。 */
        @JsonProperty("display_unit")
        String displayUnit;

        /** 有效样本数。 */
        Long validCount;

        /** 非空值计数。 */
        Long totalCount;
    }
}
