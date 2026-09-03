package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmAlarmRuleRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmDeviceLabelsDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRuleMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleDefinition;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ASM 动环报警规则 CRUD（{@code /asm-monitor/alarm-rule}）。每次变更后热加载
 * （{@link AsmAlarmRuleIndex#reload} 整体重建索引快照，同 env-alarm-manager initAlarmSettings 模式）。
 *
 * <p><b>严格模式</b>：写操作先经 {@link AsmAlarmRuleDefinition#parse} 全量校验（非法 JSON/缺 name/
 * 缺 device_info/severity 非法直接 400 语义异常，不落库不重建——坏规则在入口拦下，
 * 与运行期「坏规则隔离跳过」双保险）。</p>
 *
 * <p><b>alarmType 治理</b>：①唯一——与其他规则 alarm_type 重复 400「报警标识已存在: xxx」；
 * ②预置锁定——系统预置规则（库值 settingContent configurable!=true）更新时 alarmType 与库值不同
 * 400「系统预置报警标识不可修改」（标识被 asm_alarm_record 引用，系统规则身份不可漂移）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/alarm-rule")
@RequiredArgsConstructor
public class AsmAlarmRuleController extends BaseController {

    private final AsmAlarmRuleMapper ruleMapper;
    private final AsmAlarmRuleIndex ruleIndex;
    private final AsmDeviceLabelService labelService;

    private final Log log = LogFactory.getLogger(getClass());

    /**
     * 规则列表（全量；规则数=动环报警类型数，量级小不分页）。行 = 规则字段平铺（JSON 路径与旧响应
     * 一致）+ {@code deviceLabels}（device_info 逐槽中文标注，前端 ruoyi 化契约）。
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:list')")
    @GetMapping("/list")
    public List<AsmAlarmRuleRowDto> list() {
        List<AsmAlarmRule> rules = ruleMapper.selectAll();
        List<AsmAlarmRuleRowDto> rows = new ArrayList<>(rules.size());
        for (AsmAlarmRule rule : rules) {
            rows.add(AsmAlarmRuleRowDto.of(rule, labelsOf(rule)));
        }
        return rows;
    }

    /**
     * 行内 device_info → 逐槽中文标注。坏配置行（手改库产生；入口校验保证端点写入的行必合法）
     * 降级空列表不毒整个列表——同索引加载层「坏规则隔离跳过」纪律，行本体照常下发。
     */
    private List<AsmDeviceLabelsDto> labelsOf(AsmAlarmRule rule) {
        try {
            return labelService.deviceLabels(AsmAlarmRuleDefinition.parse(rule).getDevices());
        } catch (IllegalArgumentException e) {
            log.warn("[诊断调试] alarm-rule/list deviceLabels 解析降级为空（坏配置行隔离）: alarmType={}: {}",
                    rule.getAlarmType(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 单条规则。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:query')")
    @GetMapping("/{id}")
    public AsmAlarmRule getById(@PathVariable Long id) {
        return ruleMapper.selectById(id);
    }

    /** 新增规则（内容校验 → alarmType 唯一 → 落库 + 热加载）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:add')")
    @PostMapping
    public AjaxResult add(@RequestBody AsmAlarmRule rule) {
        validate(rule);
        requireUniqueAlarmType(rule.getAlarmType(), null);
        int rows = ruleMapper.insert(rule);
        ruleIndex.reload();
        return toAjax(rows);
    }

    /** 修改规则（内容校验 → 预置标识锁 → alarmType 唯一 → 落库 + 热加载）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:edit')")
    @PutMapping
    public AjaxResult edit(@RequestBody AsmAlarmRule rule) {
        if (rule == null || rule.getId() == null) {
            throw new IllegalArgumentException("修改规则须带 id");
        }
        validate(rule);
        AsmAlarmRule current = ruleMapper.selectById(rule.getId());
        if (current == null) {
            throw new IllegalArgumentException("规则不存在: id=" + rule.getId());
        }
        rejectPresetAlarmTypeChange(current, rule);
        requireUniqueAlarmType(rule.getAlarmType(), rule.getId());
        int rows = ruleMapper.update(rule);
        ruleIndex.reload();
        return toAjax(rows);
    }

    /** 删除规则（落库 + 热加载）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:remove')")
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id) {
        int rows = ruleMapper.deleteById(id);
        ruleIndex.reload();
        return toAjax(rows);
    }

    /** 写入口校验：alarmType 必填 + setting_content 必须能解析成合法规则定义（severity 合法性在 parse 内一并校验）。 */
    private void validate(AsmAlarmRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("规则体为空");
        }
        if (rule.getAlarmType() == null || rule.getAlarmType().trim().isEmpty()) {
            throw new IllegalArgumentException("alarmType 必填（库内唯一编码）");
        }
        AsmAlarmRuleDefinition.parse(rule);
    }

    /**
     * alarmType 唯一校验（excludeId=编辑排除自身）：与其他规则 alarm_type 重复 → 400「报警标识已存在」。
     * 规则量级小（=报警类型数）全量扫即可；DB uk_asm_alarm_rule_type 兜底并发窗口（app 校验为明确文案）。
     */
    private void requireUniqueAlarmType(String alarmType, Long excludeId) {
        for (AsmAlarmRule other : ruleMapper.selectAll()) {
            if (other.getAlarmType() != null && other.getAlarmType().equals(alarmType)
                    && (excludeId == null || !excludeId.equals(other.getId()))) {
                throw new ServiceException("报警标识已存在: " + alarmType, HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * 预置规则标识锁定：库值 settingContent {@code configurable!=true}（系统 seed 的不可配置规则——
     * 用户经端点创建的行恒 configurable:true，见前端规则编辑器）且请求 alarmType 与库值不同 →
     * 400「系统预置报警标识不可修改」。alarm_type 被 asm_alarm_record 引用，系统规则身份不可漂移；
     * 预置行的其余字段（severity/内容/启停）不受本锁约束。
     */
    private void rejectPresetAlarmTypeChange(AsmAlarmRule current, AsmAlarmRule request) {
        if (!AsmAlarmRuleDefinition.parse(current).isConfigurable()
                && !current.getAlarmType().equals(request.getAlarmType())) {
            throw new ServiceException("系统预置报警标识不可修改", HttpStatus.BAD_REQUEST);
        }
    }
}
