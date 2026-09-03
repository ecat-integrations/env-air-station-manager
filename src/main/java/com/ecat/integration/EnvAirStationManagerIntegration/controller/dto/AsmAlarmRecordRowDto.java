package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * alarm-record/list 行 = 记录行字段平铺 + ruoyi 化中文/时间契约字段（字段名固定不得改：
 * device_label / attr_label / trigger_time / recover_time / duration_ms）。
 *
 * <p>{@code @JsonUnwrapped} 平铺记录本体：alarmType/ruleName/startTime/... 的 JSON 路径与旧响应
 * 逐字段一致（追加不换口径，旧前端零改动）。</p>
 *
 * <p><b>触发时刻映射</b>：{@code trigger_time} = 库列 {@code start_time}（episode 模型下 ACTIVE 行
 * start_time=首触发、INACTIVE 行为触发起点——触发时刻的唯一库承载）；{@code recover_time} = 库列
 * {@code end_time}（闭单/恢复时刻，活跃行 null）。旧 vue AsmAlarmList.vue「触发时刻」列绑定的
 * endTime 是终态行时代的遗留绑定（当时 end_time=触发时刻），episode 模型后已漂移，新契约以
 * start_time 为触发时刻真相源（映射依据见任务报告与 bugs 记录）。</p>
 *
 * @author coffee
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsmAlarmRecordRowDto {

    /** 记录行本体（平铺序列化，见类 javadoc）。 */
    @JsonUnwrapped
    private AsmAlarmRecord record;

    /** 槽中文名（StationParamMeta label 同源；非站房槽回退 uid 原文）。 */
    @JsonProperty("device_label")
    private String deviceLabel;

    /** attr 中文 displayName（registry attrDefs 同源；解析不到回退 attrId 原文）。 */
    @JsonProperty("attr_label")
    private String attrLabel;

    /** 触发时刻（=start_time，见类 javadoc 映射依据）。 */
    @JsonProperty("trigger_time")
    private Instant triggerTime;

    /** 恢复时刻（=end_time；活跃行 null）。 */
    @JsonProperty("recover_time")
    private Instant recoverTime;

    /** 持续时长毫秒（=end_time−start_time；活跃行 end_time=null → null，不猜）。 */
    @JsonProperty("duration_ms")
    private Long durationMs;

    public static AsmAlarmRecordRowDto of(AsmAlarmRecord record, String deviceLabel, String attrLabel) {
        return AsmAlarmRecordRowDto.builder()
                .record(record)
                .deviceLabel(deviceLabel)
                .attrLabel(attrLabel)
                .triggerTime(record.getStartTime())
                .recoverTime(record.getEndTime())
                .durationMs(durationMs(record))
                .build();
    }

    /** 持续时长：起点/终点任一缺失（活跃行 end=null）返 null——不虚构时长。 */
    public static Long durationMs(AsmAlarmRecord record) {
        return record.getStartTime() != null && record.getEndTime() != null
                ? Long.valueOf(Duration.between(record.getStartTime(), record.getEndTime()).toMillis())
                : null;
    }
}
