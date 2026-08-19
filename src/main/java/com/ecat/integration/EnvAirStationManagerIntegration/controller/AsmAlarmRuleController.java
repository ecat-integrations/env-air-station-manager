package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRuleMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleDefinition;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
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

import java.util.List;

/**
 * ASM 动环报警规则 CRUD（{@code /asm-monitor/alarm-rule}）。每次变更后热加载
 * （{@link AsmAlarmRuleIndex#reload} 整体重建索引快照，同 env-alarm-manager initAlarmSettings 模式）。
 *
 * <p><b>严格模式</b>：写操作先经 {@link AsmAlarmRuleDefinition#parse} 全量校验（非法 JSON/缺 name/
 * 缺 device_info/severity 非法直接 400 语义异常，不落库不重建——坏规则在入口拦下，
 * 与运行期「坏规则隔离跳过」双保险）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/alarm-rule")
@RequiredArgsConstructor
public class AsmAlarmRuleController extends BaseController {

    private final AsmAlarmRuleMapper ruleMapper;
    private final AsmAlarmRuleIndex ruleIndex;

    /** 规则列表（全量；规则数=动环报警类型数，量级小不分页）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:list')")
    @GetMapping("/list")
    public List<AsmAlarmRule> list() {
        return ruleMapper.selectAll();
    }

    /** 单条规则。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:query')")
    @GetMapping("/{id}")
    public AsmAlarmRule getById(@PathVariable Long id) {
        return ruleMapper.selectById(id);
    }

    /** 新增规则（校验通过后落库 + 热加载）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:add')")
    @PostMapping
    public AjaxResult add(@RequestBody AsmAlarmRule rule) {
        validate(rule);
        int rows = ruleMapper.insert(rule);
        ruleIndex.reload();
        return toAjax(rows);
    }

    /** 修改规则（校验通过后落库 + 热加载）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRule:edit')")
    @PutMapping
    public AjaxResult edit(@RequestBody AsmAlarmRule rule) {
        if (rule == null || rule.getId() == null) {
            throw new IllegalArgumentException("修改规则须带 id");
        }
        validate(rule);
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

    /** 写入口校验：setting_content 必须能解析成合法规则定义（severity 合法性在 parse 内一并校验）。 */
    private void validate(AsmAlarmRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("规则体为空");
        }
        AsmAlarmRuleDefinition.parse(rule);
    }
}
