package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * SDK snapshot 行——一个 attr 的当前值投影（live 唯一源；无值参数=DEF 占位行，值字段全 null 显无数据）。
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

    /** value 实际单位符号（UnitInfo.getName，如 °C/V；null=无量纲。与 snapshot REST 出口同口径）。 */
    String unit;

    /** 值时刻（LIVE=实时态；DEF 占位行 null）。 */
    Instant updateTime;

    /** 值来源：LIVE=实时态 / DEF=无值占位（按无数据处理）。 */
    String source;
}
