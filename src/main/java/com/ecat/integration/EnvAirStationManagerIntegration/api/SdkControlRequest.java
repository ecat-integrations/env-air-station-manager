package com.ecat.integration.EnvAirStationManagerIntegration.api;

import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;

import lombok.Builder;
import lombok.Value;

/**
 * SDK 控制写请求（{@link AirStationSdk#control(SdkControlRequest)} 入参，不可变 @Value/@Builder）。
 *
 * <p><b>为什么收成请求对象</b>：origin/unit 语义全部由调用方显式声明，集成不代调用方做任何隐式决定
 * （origin 决定权归 SDK 调用方；四参时代 origin 被硬编码 LOCAL，第三方代传远程侧指令的场景无法表达）。</p>
 *
 * <p><b>参数约束与校验时序</b>：下表逐条为<b>提交受理前同步 fail-fast</b> 校验，任一不满足抛
 * {@link IllegalArgumentException} / {@link IllegalStateException}、<b>不落审计行</b>；提交受理后
 * 的失败走异步终态（PENDING→SUCCESS/FAILED/TIMEOUT，经 recordId 回查）——两类失败不混：</p>
 *
 * <table border="1">
 *   <tr><th>字段</th><th>约束</th></tr>
 *   <tr><td>uid</td><td>非空白；须 {@code logicdevice_station.} 前缀；设备须已注册，否则同步拒绝</td></tr>
 *   <tr><td>attrId</td><td>非空白；属性须存在且可写（canValueChange=true，不可写同步抛 IllegalStateException）</td></tr>
 *   <tr><td>value</td><td>非空白。数值属性=数字串；Command/Select/Binary=选项 key（选项非法<b>不</b>在提交时校验，
 *       属执行期失败→异步 FAILED）</td></tr>
 *   <tr><td>unit</td><td><b>null 非法</b>（区分「漏传」与「明确不指定」）；<b>空串=不指定单位</b>（按属性默认单位，
 *       即不带单位换算的现状语义）；<b>非空=单位 full string</b>「枚举类名.枚举常量名」（如
 *       {@code TemperatureUnit.CELSIUS}，全系统传输口径；{@code °C}/{@code mA} 等符号是展示层
 *       getName 输出，禁止作传输值）；须与属性同量纲（跨量纲换算失败属执行期失败→异步 FAILED）</td></tr>
 *   <tr><td>origin</td><td>二值枚举，null 非法。LOCAL=本站/集成自身发起（本站 web 页面、站内报警联动）；
 *       REMOTE=第三方集成代传的远程侧指令（最终用户信息落 caller）</td></tr>
 *   <tr><td>caller</td><td>非空白自由串（不约束格式）。LOCAL=发起方集成坐标（如
 *       {@code com.ecat:integration-xxx}）；REMOTE=最终用户标识（如 {@code platformA:user123}）</td></tr>
 * </table>
 *
 * <p><b>unit 空串 vs null 的设计动机</b>：HTTP/JSON 与进程内调用都存在「字段没带」与「带了但明确为空」
 * 两种形态，语义不同——null 视为调用方 bug（漏传）必须拒绝；空串视为调用方明确表态「不做单位换算，
 * 按属性默认单位写入」。把「漏传」静默降级成「不指定单位」会掩盖上游契约破坏。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkControlRequest {

    /** 站房逻辑设备 uniqueId（{@code logicdevice_station.*} 前缀）。 */
    String uid;

    /** 可写属性 id（须存在且 canValueChange=true）。 */
    String attrId;

    /** 请求值：数值属性=数字串；Command/Select/Binary=选项 key（选项非法→异步 FAILED）。 */
    String value;

    /**
     * 请求值单位：null 非法；空串=不指定单位（按属性默认单位）；非空=full string
     * 「枚举类名.枚举常量名」（如 {@code TemperatureUnit.CELSIUS}），禁止展示层符号（°C/mA）。
     */
    String unit;

    /** 调用来源（LOCAL=本站/集成自身发起；REMOTE=第三方代传远程侧指令）。 */
    AsmControlOrigin origin;

    /** 调用方标识（LOCAL=集成坐标；REMOTE=最终用户标识），自由串，落审计 caller 列。 */
    String caller;
}
