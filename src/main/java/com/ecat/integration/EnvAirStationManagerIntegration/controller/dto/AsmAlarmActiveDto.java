package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * snapshot 设备级活跃报警条目（报警心跳窗）。
 *
 * <p>来源是两源并集（registry 内存徽章槽 ∪ 设备 alarm_status attr 聚合值），按 attrId+alarmType
 * 粗粒度去重——两源口径不同不算重复（registry 是规则引擎判定的活跃 episode；alarm_status 是设备
 * 自报的报警状态位，独立可信）。alarm_type={@code ALARM_STATUS} 标记 attr 侧来源条目。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class AsmAlarmActiveDto {

    /** attr 侧来源标记（alarm_status attr 自报报警，非规则引擎身份）。 */
    public static final String TYPE_ATTR_STATUS = "ALARM_STATUS";

    /** logic attr id。 */
    String attrId;

    /** 参数中文名（attr def displayName，缺省回退 attrId）。 */
    String displayName;

    /** 报警类型编码（规则引擎身份；attr 侧来源为 {@link #TYPE_ATTR_STATUS}）。 */
    String alarmType;

    /** 规则名快照（attr 侧来源为 alarm_status 当前状态文本）。 */
    String ruleName;

    /** 报警起始时刻（首触发；attr 侧来源=该状态最后更新时刻）。 */
    Instant startTime;
}
