package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * SDK 报警记录行——不可变 DTO（api 包零依赖契约）。
 *
 * @author coffee
 */
@Value
@Builder
public class SdkAlarmRecord {

    String alarmType;
    String ruleName;
    String logicDeviceUniqueId;
    String attrId;
    /** 0普通/1重要/2紧急（规则级配置）。 */
    String severity;
    Instant startTime;
    Instant endTime;
    String description;
    /** 生命周期态：ACTIVE（活跃中，endTime=null）/ INACTIVE（已闭单）。 */
    String status;
    String resultContent;
}
