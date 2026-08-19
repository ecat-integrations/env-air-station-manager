package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * SDK snapshot 行——一个 attr 的当前值投影（live 实时态优先，raw 最新值回放兜）。
 *
 * <p>不可变 DTO（对外稳定契约包成员）。数值经 MONITOR 读出口换算，{@link #getUnit()} 恒为
 * {@link #getValue()} 的实际单位。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkSnapshotAttr {

    /** logic attr id。 */
    String attrId;

    /** 数值业务值（null=非数值属性或无值）。 */
    Double value;

    /** 非数值展示串（null=数值属性）。 */
    String valueText;

    /** value 实际单位（full key；null=无量纲）。 */
    String unit;

    /** 值时刻（LIVE=实时态 / RAW=样本时刻）。 */
    Instant updateTime;

    /** 值来源：LIVE / RAW。 */
    String source;
}
