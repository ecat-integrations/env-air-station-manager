package com.ecat.integration.EnvAirStationManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * SDK 报警标识目录条目——一个合法可查报警标识的元数据投影
 * （{@code AirStationSdk#listAlarmTypes()} 返回行，模式同 {@link SdkParamMeta}）。
 *
 * <p>消费方据此得知 queryAlarmEntries 可传哪些 alarmType，不必翻 DB。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkAlarmTypeMeta {

    /** 报警标识（语义化 string，全局唯一，供 queryAlarmEntries 作查询键）。 */
    String alarmType;
    /** 规则名（setting_content.name）。 */
    String ruleName;
    /** 级别原始码 '0'普通/'1'重要/'2'紧急。 */
    String severity;
}
