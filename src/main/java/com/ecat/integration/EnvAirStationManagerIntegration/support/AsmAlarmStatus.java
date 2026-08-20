package com.ecat.integration.EnvAirStationManagerIntegration.support;

/**
 * asm_alarm_record.status 生命周期态（心跳窗模型，镜像 ADM adm_alarm）。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：活跃报警——start_time=首触发，end_time=null，last_breach_time=每次命中续期；</li>
 *   <li>{@link #INACTIVE}：已闭单——sweep 过窗 / POWER 恢复主动闭，end_time=闭单时刻。</li>
 * </ul>
 *
 * <p>历史「"0"=活跃」语义废弃（无生产，DDL 直改；存量行迁移时全部置 INACTIVE）。</p>
 *
 * @author coffee
 */
public enum AsmAlarmStatus {

    /** 活跃报警（窗口内持续续期中）。 */
    ACTIVE,

    /** 已闭单（窗口过期满 / 断电恢复）。 */
    INACTIVE
}
