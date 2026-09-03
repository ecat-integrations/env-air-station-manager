package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * alarm-rule/list 行 = 规则行字段平铺 + {@code deviceLabels}（前端 ruoyi 化中文契约，字段名固定不得改）。
 *
 * <p>{@code @JsonUnwrapped} 平铺规则本体：id/alarmType/severity/settingContent/sort/createdAt/updatedAt
 * 的 JSON 路径与旧 {@code List<AsmAlarmRule>} 响应逐字段一致（追加不换口径，旧前端零改动）。</p>
 *
 * @author coffee
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsmAlarmRuleRowDto {

    /** 规则行本体（平铺序列化，见类 javadoc）。 */
    @JsonUnwrapped
    private AsmAlarmRule rule;

    /** 行内 device_info 逐槽中文标注（与 device_info uid 顺序一一对应；坏配置行降级空列表，见 controller）。 */
    private List<AsmDeviceLabelsDto> deviceLabels;

    public static AsmAlarmRuleRowDto of(AsmAlarmRule rule, List<AsmDeviceLabelsDto> deviceLabels) {
        return AsmAlarmRuleRowDto.builder().rule(rule).deviceLabels(deviceLabels).build();
    }
}
