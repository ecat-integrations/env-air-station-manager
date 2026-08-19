package com.ecat.integration.EnvAirStationManagerIntegration.service;

import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * minute←raw 物化的源行（JdbcTemplate fetch 的窄投影：data_time + value_num）。
 *
 * <p>包级可见——只服务 {@link AsmStatAggregationEngine} minute 聚合路径（raw 样本无须 statuses/unit，
 * ASM avg-only 无单位换算无有效性四态）。测试用 {@link #of} 造桩行。</p>
 *
 * @author coffee
 */
@Value
class AsmRawRow {

    Instant dataTime;
    BigDecimal valueNum;

    static AsmRawRow of(Instant dataTime, BigDecimal valueNum) {
        return new AsmRawRow(dataTime, valueNum);
    }
}
