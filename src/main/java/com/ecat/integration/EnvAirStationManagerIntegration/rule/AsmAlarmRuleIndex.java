package com.ecat.integration.EnvAirStationManagerIntegration.rule;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRuleMapper;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报警规则内存索引——uid→attrId→rules 嵌套 Map（修复点1：禁 uniqueId+attrId 字符串拼接 key，
 * "a.b"+"c" 与 "a"+"b.c" 拼接同 key 会串台；嵌套 Map 天然无碰撞）。
 *
 * <p><b>坏配置隔离</b>：{@link #reload()} 逐行 parse，单行坏（JSON 非法/缺 name/缺 device_info/
 * severity 非法）记 error 日志 + 跳过该行，其余规则照常入索引——单条规则坏不得毒死整个引擎。</p>
 *
 * <p><b>热加载</b>：规则 CRUD 变更后调 {@link #reload()} 整体重建快照（volatile 引用换新，
 * 评估线程无锁读；同 env-alarm-manager initAlarmSettings 模式）。@Service 非 @Component：
 * 动态 jar 单例注册只认 @RestController/@Service。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmAlarmRuleIndex {

    private final Log log = LogFactory.getLogger(getClass());

    private final AsmAlarmRuleMapper ruleMapper;

    /** 索引快照：uid→attrId→该 series 上生效的规则定义（volatile 整体替换）。 */
    private volatile Map<String, Map<String, List<AsmAlarmRuleDefinition>>> index = Collections.emptyMap();

    /** 已成功加载的规则数（坏规则隔离后；诊断用）。 */
    private volatile int loadedRuleCount;

    /** 从 mapper 全量重建索引（启动 + CRUD 热加载共用入口；坏行隔离见 installRows）。 */
    public void reload() {
        installRows(ruleMapper.selectAll());
    }

    /**
     * 用已解析规则定义列表整体替换索引快照。
     *
     * @param definitions 解析层负责坏行隔离（reload 传入前逐行 parse + 跳过）
     */
    void install(List<AsmAlarmRuleDefinition> definitions) {
        Map<String, Map<String, List<AsmAlarmRuleDefinition>>> next = new LinkedHashMap<>();
        int loaded = 0;
        for (AsmAlarmRuleDefinition def : definitions) {
            for (Map.Entry<String, List<String>> e : def.getDevices().entrySet()) {
                Map<String, List<AsmAlarmRuleDefinition>> byAttr =
                        next.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                for (String attrId : e.getValue()) {
                    byAttr.computeIfAbsent(attrId, k -> new ArrayList<>()).add(def);
                }
            }
            loaded++;
        }
        this.index = next;
        this.loadedRuleCount = loaded;
    }

    /**
     * reload 专用：规则行逐行 parse（坏行 error 日志 + 跳过）后安装。
     */
    public void installRows(List<AsmAlarmRule> rows) {
        List<AsmAlarmRuleDefinition> defs = new ArrayList<>(rows.size());
        for (AsmAlarmRule row : rows) {
            try {
                defs.add(AsmAlarmRuleDefinition.parse(row));
            } catch (IllegalArgumentException e) {
                log.error("[诊断调试] 报警规则配置坏，已跳过（其余规则继续生效）: alarmType=" + row.getAlarmType()
                        + ", 原因: " + e.getMessage());
            }
        }
        install(defs);
    }

    /** 取 series 上生效的规则列表（无规则返空列表，永不 null）。 */
    public List<AsmAlarmRuleDefinition> getRules(String uid, String attrId) {
        Map<String, List<AsmAlarmRuleDefinition>> byAttr = index.get(uid);
        if (byAttr == null) {
            return Collections.emptyList();
        }
        List<AsmAlarmRuleDefinition> rules = byAttr.get(attrId);
        return rules == null ? Collections.emptyList() : rules;
    }

    /** 成功加载规则数（含同一规则多 series 展开，与 install 行数一致口径见 loaded 字段）。 */
    public int loadedRuleCount() {
        return loadedRuleCount;
    }
}
