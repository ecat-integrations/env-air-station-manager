package com.ecat.integration.EnvAirStationManagerIntegration.api;

import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;

import java.time.Instant;
import java.util.List;

/**
 * ASM stat/snapshot 数据对外查询 SDK——外部集成访问 {@code asm_stat_*} 聚合数据与站房设备当前态的
 * 稳定契约入口（设计 §5，api 包模式同 ADM {@code AirDeviceDataSdk} 先例）。
 *
 * <p><b>对外稳定契约包</b>：本 api 包只含接口 + 不可变 DTO，零 ruoyi/vue/Spring/core 依赖；
 * 外部消费方 maven 依赖 ASM jar（provided）只 import 本包。内部实现（service/mapper）不得反向泄漏进本包
 * ——粒度/区间模式直接复用内部 {@link AsmStatGranularity}/{@link AsmIntervalMode} 纯枚举（零复制）。</p>
 *
 * <p><b>获取方式</b>（进程内 registry，不经 Spring bean——不把 Spring 语义漏给外部）：</p>
 * <pre>{@code
 * AirStationSdk sdk = ((com.ecat.integration.EnvAirStationManagerIntegration.EnvAirStationManagerIntegration)
 *         core.getIntegrationRegistry()
 *              .getIntegration("com.ecat:integration-env-air-station-manager"))
 *         .getAirStationSdk();
 * }</pre>
 *
 * <p><b>语义要点</b>：</p>
 * <ul>
 *   <li><b>batch 单 SQL</b>：N 参数一次往返（tuple IN-list），禁 N+1；结果按 dataTime 升序。</li>
 *   <li><b>value = STORAGE 存储单位桶均值</b>（机对机口径，不做展示偏好换算）；unit 恒为 value 实际单位
 *       （asm_config_unit STORAGE 行 full key；null=无量纲）。非数值 series（ALARM/STATE）value=null、
 *       取 SdkStatRow.valueText（ALARM: normal/alarm；STATE: 状态串；unit=空串显无单位）。</li>
 *   <li><b>querySnapshot</b>：live 实时态优先（经 MONITOR 读出口换算），raw 最新值回放兜（source 标记 LIVE/RAW）。</li>
 *   <li><b>无缓存</b>：直连 mapper（外部轮询分钟级，stat 查询毫秒级）。</li>
 * </ul>
 *
 * <p><b>严格模式入参校验</b>（任一不满足抛 {@link IllegalArgumentException}，明确告知不静默兜底）：</p>
 * <ul>
 *   <li>params 非空；mode/granularity/start/end 非 null；start &lt; end。</li>
 *   <li>单次窗口上限：MINUTE|FIVE_MIN ≤31 天、HOUR ≤400 天（超限抛，消息含粒度/上限/实际值）。</li>
 * </ul>
 *
 * @author coffee
 */
public interface AirStationSdk {

    /**
     * batch 查询多参数的 stat 桶行。
     *
     * <p>数值 series 桶行 value=STORAGE 存储单位桶均值；非数值 series（ALARM/STATE）value=null、
     * valueText=非数值统计值（ALARM: normal/alarm；STATE: 状态串）原样透传。</p>
     *
     * @param params      参数键列表，一次 SQL tuple IN 查询
     * @param granularity 统计粒度（MINUTE/FIVE_MIN/HOUR → asm_stat_minute/_5min/_hour）
     * @param mode        区间模式（BACK/FRONT），须与物化 scope 一致否则返空
     * @param start       窗口起（含，UTC instant）
     * @param end         窗口止（不含，UTC instant）
     * @return 桶行列表（dataTime 升序）；无数据返空列表
     * @throws IllegalArgumentException params 空 / 枚举或时刻 null / start≥end / 窗口超粒度上限
     */
    List<SdkStatRow> queryStat(List<AsmParamKey> params,
                               AsmStatGranularity granularity,
                               AsmIntervalMode mode,
                               Instant start,
                               Instant end);

    /**
     * 动态列出 ASM 当前可查询的全部 stat 参数（参数目录清单）。
     *
     * <p>真相源：{@code asm_config_stat}（granularity_mask）JOIN {@code asm_config_unit} STORAGE 行
     * （storage unit）。按 logicDeviceUniqueId/attrId 升序稳定清单序；配置未 seed 时返空列表。</p>
     *
     * @return 参数元数据列表
     */
    List<SdkParamMeta> listStatParams();

    /**
     * 查单台站房设备当前态（实时快照）。
     *
     * @param uid 站房逻辑设备 uniqueId（logicdevice_station.*）
     * @return 属性行列表（live 优先/raw 回放兜）；registry 无该设备返空列表
     * @throws IllegalArgumentException uid null/blank
     */
    List<SdkSnapshotAttr> querySnapshot(String uid);

    /**
     * 查询指定报警标识在时间段内的报警条目（含持续中尚未恢复的）。
     *
     * <p><b>窗口语义 = episode 区间重叠，查询窗左开右闭 {@code (start, end]}</b>：报警 episode 与开区间
     * 时刻集 {@code (start, end]} 有交集即命中。边界（跨窗连续查询——上窗 end=下窗 start——无缝无重）：</p>
     * <ul>
     *   <li>右闭 {@code start_time <= end}：end 时刻触发的报警<b>算本期</b>，不漏；</li>
     *   <li>左开 {@code end_time > start}：恰在 start 时刻闭单的报警<b>归上一期</b>，不重。</li>
     * </ul>
     * <p>持续中尚未恢复的报警（end_time=null 的 ACTIVE 行）episode 开区间到 +∞，天然命中——覆盖
     * 窗内闭单 / 窗前开始窗内闭 / 窗内开始窗后闭 / 窗前开始仍未恢复全部场景。</p>
     *
     * <p>行形状与前端报警表格列一致（状态/规则名/设备/参数/级别/触发时刻/恢复时刻/持续时长/报警详情）
     * 外加 alarmType 查询键回显；label 双字段解析不到为 null（机对机口径不内联回退，见
     * {@link SdkAlarmEntry}）。</p>
     *
     * @param alarmType 报警标识（语义化 string；合法值可经 {@link #listAlarmTypes()} 枚举）
     * @param start     窗口起（开，UTC instant）
     * @param end       窗口止（闭，UTC instant）
     * @param limit     行数上限（1..1000）
     * @return 报警条目列表（triggerTime 降序）；无数据返空列表
     * @throws IllegalArgumentException alarmType 空白 / 时刻 null / start≥end / limit 越界
     */
    List<SdkAlarmEntry> queryAlarmEntries(String alarmType, Instant start, Instant end, int limit);

    /**
     * 动态列出 ASM 当前全部合法报警标识目录（asm_alarm_rule 全量投影）。
     *
     * <p>模式同 {@link #listStatParams()}：调用者据此得知 queryAlarmEntries 可查标识，不必翻 DB。
     * 坏配置行隔离跳过（同规则索引语义，目录只列合法标识）；按 alarmType 升序稳定清单序。</p>
     *
     * @return 报警标识元数据列表（alarmType/ruleName/severity）；配置未 seed 时返空列表
     */
    List<SdkAlarmTypeMeta> listAlarmTypes();

    /**
     * P4：执行一次站房设备控制写（origin=LOCAL，经统一控制服务收口、全程落 asm_control_record 审计）。
     *
     * <p>同步返回审计行投影（recordId + 当前 result）；执行异步完成回填终态（SUCCESS/FAILED/TIMEOUT），
     * 终态亦可按 recordId 回查。非法入参（uid/attrId/value/caller 空白、未知设备/属性、不可写属性）
     * 抛 {@link IllegalArgumentException} / {@link IllegalStateException}，明确不静默。</p>
     *
     * @param uid    站房逻辑设备 uniqueId（logicdevice_station.*）
     * @param attrId 可写属性 id（Command 型传选项 key）
     * @param value  请求值
     * @param caller 消费方集成坐标（严格非空，落审计 caller 列）
     * @return 控制结果行（recordId/result/error/durationMs）
     */
    SdkControlResult control(String uid, String attrId, String value, String caller);
}
