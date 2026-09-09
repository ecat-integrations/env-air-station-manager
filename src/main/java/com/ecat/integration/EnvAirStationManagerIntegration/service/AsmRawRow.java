package com.ecat.integration.EnvAirStationManagerIntegration.service;

import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * minute←raw 物化的源行（JdbcTemplate fetch 的窄投影：data_time + value_num + value_text）。
 *
 * <p>包级可见——只服务 {@link AsmStatAggregationEngine} minute 聚合路径（raw 样本无须 statuses/unit，
 * ASM 无单位换算无有效性四态）。value_num/value_text 与 raw 表列同名同义互斥：数值 series 样本带
 * value_num、非数值 series（ALARM/STATE）带 value_text，另一槽恒 null。</p>
 *
 * <p>测试用 {@link #of} 造桩行（双参重载服务数值桩，文本槽留 null）。</p>
 *
 * @author coffee
 */
@Value
class AsmRawRow {

    Instant dataTime;
    BigDecimal valueNum;
    String valueText;

    static AsmRawRow of(Instant dataTime, BigDecimal valueNum) {
        return new AsmRawRow(dataTime, valueNum, null);
    }

    static AsmRawRow of(Instant dataTime, BigDecimal valueNum, String valueText) {
        return new AsmRawRow(dataTime, valueNum, valueText);
    }
}
