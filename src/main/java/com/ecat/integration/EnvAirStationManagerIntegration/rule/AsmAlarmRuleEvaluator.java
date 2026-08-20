package com.ecat.integration.EnvAirStationManagerIntegration.rule;

import com.alibaba.fastjson2.JSONObject;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmAlarmStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASM 动环报警规则评估器（语义移植 env-alarm-manager EnvAlarmEventHandler 三类判定，
 * 业务语义移植、代码重写；airdevice 分析仪域规则不移植，只服务 logicdevice_station.* 站房域）。
 *
 * <ul>
 *   <li><b>range+duration</b>：超限首见记 CreateTime、持续中刷新、恢复正常清零；持续满 N 分钟触发
 *       （对齐原 checkAlarmRMTempAbnormal 等，且修复其 configs 顺序耦合与 copy-paste key 错误——
 *       本实现 duration key 统一取自 SeriesRuleKey 复合 key，修复点2）；</li>
 *   <li><b>瞬时阈值</b>：单值 gt/lt 越限即触发（阈值 per-attrId 参数化，修复点3）；</li>
 *   <li><b>状态串</b>：equals/contains 匹配（默认匹配集 报警/ON/低报警/高报警，对齐原域内语义）；</li>
 *   <li><b>断电+恢复</b>：低于阈值报警，恢复后 force 记录「恢复」（不去重，对齐原 insertAlarmForce）。</li>
 * </ul>
 *
 * <p><b>心跳窗生命周期</b>（替代旧 5min 去重 alarmCache）：每次命中都产出触发记录（status=ACTIVE、
 * end_time=null、last_breach_time=now），续期/新插/闭单由 {@code AsmAlarmLifecycleService} +
 * {@code AsmAlarmSweepScheduler} 收口（镜像 ADM 单一身份 extend-or-insert 模型）。
 * <b>时间源</b>注入 {@link Clock}（测试手动推进，禁 sleep）。</p>
 *
 * <p><b>运行时隔离</b>：单规则评估抛异常（如数值规则遇非数值 displayValue）记 error 日志跳过，
 * 同 series 其余规则照常评估——与索引层解析隔离双保险。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmAlarmRuleEvaluator {

    private final Log log = LogFactory.getLogger(getClass());

    private final AsmAlarmRuleIndex ruleIndex;

    /** 时间源（生产系统钟；测试注入手动推进的钟，禁 sleep 同步）。 */
    private Clock clock = Clock.systemDefaultZone();

    /** 测试注入时间源。 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 复合 key：修复点1 字符串拼接碰撞（"a.b"+"c" vs "a"+"b.c"）。 */
    private static final class SeriesRuleKey {
        private final String uid;
        private final String attrId;
        private final String alarmType;
        SeriesRuleKey(String uid, String attrId, String alarmType) {
            this.uid = uid;
            this.attrId = attrId;
            this.alarmType = alarmType;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof SeriesRuleKey)) {
                return false;
            }
            SeriesRuleKey k = (SeriesRuleKey) o;
            return uid.equals(k.uid) && attrId.equals(k.attrId) && alarmType.equals(k.alarmType);
        }
        @Override public int hashCode() {
            return Objects.hash(uid, attrId, alarmType);
        }
    }

    /** 持续时间起算表（key=复合 SeriesRuleKey；value=首超限时刻，恢复正常即移除）。 */
    private final Map<SeriesRuleKey, Instant> durationMap = new ConcurrentHashMap<>();

    /**
     * 评估一次属性更新事件：该 series 上每条规则独立评估，命中产出待落库记录（0..N 条）。
     *
     * @param uid          站房逻辑设备 uniqueId
     * @param attrId       参数 id
     * @param displayValue 显示值（null 直接跳过，对齐原语义）
     * @param updateTime   事件时刻（持续类起算源）
     * @return 触发的报警记录列表（由调用方落 asm_alarm_record）
     */
    public List<AsmAlarmRecord> evaluate(String uid, String attrId, String displayValue, Instant updateTime) {
        if (displayValue == null) {
            return Collections.emptyList();
        }
        List<AsmAlarmRuleDefinition> rules = ruleIndex.getRules(uid, attrId);
        if (rules.isEmpty()) {
            return Collections.emptyList();
        }
        List<AsmAlarmRecord> triggered = new ArrayList<>(rules.size());
        for (AsmAlarmRuleDefinition rule : rules) {
            try {
                AsmAlarmRecord record = evaluateRule(rule, uid, attrId, displayValue, updateTime);
                if (record != null) {
                    triggered.add(record);
                }
            } catch (Exception e) {
                log.error("[诊断调试] 报警规则评估异常，已跳过该规则（其余规则继续评估）: alarmType="
                        + rule.getAlarmType() + " uid=" + uid + " attrId=" + attrId + " value=" + displayValue
                        + ", 原因: " + e.getMessage());
            }
        }
        return triggered;
    }

    private AsmAlarmRecord evaluateRule(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                        String displayValue, Instant updateTime) {
        if (!rule.isEnabled()) {
            return null;
        }
        switch (rule.getCheck()) {
            case RANGE_DURATION:
                return checkRangeDuration(rule, uid, attrId, displayValue, updateTime);
            case INSTANT_THRESHOLD:
                return checkInstantThreshold(rule, uid, attrId, displayValue, updateTime);
            case STATUS_MATCH:
                return checkStatusMatch(rule, uid, attrId, displayValue, updateTime);
            case POWER:
                return checkPower(rule, uid, attrId, displayValue, updateTime);
            default:
                throw new IllegalStateException("未知判定类型: " + rule.getCheck());
        }
    }

    // ===== ① range + duration =====

    private AsmAlarmRecord checkRangeDuration(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                              String displayValue, Instant updateTime) {
        if (!rule.isConfigurable()) {
            return null; // 原语义：未配置的持续类规则暂不生效
        }
        double value = parseDouble(displayValue, rule, uid, attrId);
        SeriesRuleKey key = new SeriesRuleKey(uid, attrId, rule.getAlarmType());
        boolean exceed = value < rule.getRangeMin() || value > rule.getRangeMax();
        if (!exceed) {
            durationMap.remove(key); // 恢复正常，清零计时
            return null;
        }
        Instant start = durationMap.computeIfAbsent(key, k -> updateTime);
        Instant now = clock.instant();
        if (now.isBefore(start.plus(Duration.ofMinutes(rule.getDurationMinutes())))) {
            return null; // 持续时间未达到
        }
        durationMap.remove(key); // 触发后清零，重新起算
        String description = "规则[" + rule.getName() + "] 设备:" + uid + " 参数:" + attrId
                + " 超出阈值范围 [" + rule.getRangeMin() + "," + rule.getRangeMax() + "] 且持续超过 "
                + rule.getDurationMinutes() + "分钟";
        return insertAlarm(rule, uid, attrId, start, now, description,
                detail(value, "range", rule.getRangeMin() + "~" + rule.getRangeMax()));
    }

    // ===== ② 瞬时阈值 =====

    private AsmAlarmRecord checkInstantThreshold(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                                 String displayValue, Instant updateTime) {
        if (!rule.isConfigurable()) {
            return null;
        }
        Double threshold = rule.getThresholdFor(attrId);
        if (threshold == null) {
            // 阈值按 attrId 参数化：本 attr 无阈值配置=规则未覆盖该参数，跳过（原语义 config is null return）
            return null;
        }
        double value = parseDouble(displayValue, rule, uid, attrId);
        boolean gt = "gt".equals(rule.getCompareFor(attrId));
        boolean exceed = gt ? value > threshold : value < threshold;
        if (!exceed) {
            return null;
        }
        Instant now = clock.instant();
        String description = "规则[" + rule.getName() + "] 设备:" + uid + " 参数:" + attrId
                + (gt ? " 超过阈值, 当前值:" : " 低于阈值, 当前值:") + value + " 阈值:" + threshold;
        return insertAlarm(rule, uid, attrId, updateTime, now, description, detail(value, "threshold", threshold));
    }

    // ===== ③ 状态串 =====

    private AsmAlarmRecord checkStatusMatch(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                            String displayValue, Instant updateTime) {
        boolean hit;
        if ("contains".equals(rule.getStatusMatchMode())) {
            hit = false;
            for (String v : rule.getStatusMatchValues()) {
                if (displayValue.contains(v)) {
                    hit = true;
                    break;
                }
            }
        } else {
            hit = rule.getStatusMatchValues().contains(displayValue);
        }
        if (!hit) {
            return null;
        }
        Instant now = clock.instant();
        String description = "规则[" + rule.getName() + "] 设备:" + uid + " 参数:" + attrId
                + " 状态触发, 当前值:" + displayValue;
        return insertAlarm(rule, uid, attrId, updateTime, now, description, detail(displayValue, "status", null));
    }

    // ===== 断电 + 恢复（唯一保留「恢复」语义，对齐原 checkAlarmPower 15）=====

    private AsmAlarmRecord checkPower(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                      String displayValue, Instant updateTime) {
        Double threshold = rule.getThresholdFor("power");
        if (threshold == null) {
            throw new IllegalStateException("POWER 规则 " + rule.getAlarmType() + " 缺 number[class=power] 阈值");
        }
        double value = parseDouble(displayValue, rule, uid, attrId);
        SeriesRuleKey key = new SeriesRuleKey(uid, attrId, rule.getAlarmType());
        Instant now = clock.instant();
        if (value < threshold) {
            durationMap.put(key, updateTime); // 记断电窗口起点（恢复判据）
            String description = "规则[" + rule.getName() + "] 设备:" + uid + " 站房出现断电情况。 当前值:" + value;
            return insertAlarm(rule, uid, attrId, updateTime, now, description,
                    detail(value, "power_below", threshold));
        }
        if (durationMap.remove(key) != null) {
            // 曾断电且已恢复 → force 记录恢复（不走去重，对齐原 insertAlarmForce）
            String description = "规则[" + rule.getName() + "] 设备:" + uid + " 站房断电已经恢复。 当前值:" + value;
            return forceInsert(rule, uid, attrId, updateTime, now, description,
                    detail(value, "power_recovered", threshold));
        }
        return null;
    }

    // ===== 落库口径 =====

    /** 去重窗口内抑制；命中后刷 cache 并产出记录（severity 来自规则配置，修复点4）。 */
    private AsmAlarmRecord insertAlarm(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                       Instant startTime, Instant now, String description, String resultContent) {
        return buildRecord(rule, uid, attrId, startTime, now, description, resultContent);
    }

    /** 恢复类记录：终态 INACTIVE 行（end_time=恢复时刻）+ recovery 标记（生命周期层据此闭 ACTIVE 行）。 */
    private AsmAlarmRecord forceInsert(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                       Instant startTime, Instant now, String description, String resultContent) {
        AsmAlarmRecord record = buildRecord(rule, uid, attrId, startTime, now, description, resultContent);
        record.setStatus(AsmAlarmStatus.INACTIVE.name());
        record.setEndTime(now);
        record.setRecovery(true);
        return record;
    }

    private static AsmAlarmRecord buildRecord(AsmAlarmRuleDefinition rule, String uid, String attrId,
                                              Instant startTime, Instant now, String description,
                                              String resultContent) {
        return AsmAlarmRecord.builder()
                .alarmType(rule.getAlarmType())
                .ruleName(rule.getName())
                .logicDeviceUniqueId(uid)
                .attrId(attrId)
                .severity(rule.getSeverity())
                .startTime(startTime)
                .endTime(null)
                .description(description)
                .status(AsmAlarmStatus.ACTIVE.name())
                .lastBreachTime(now)
                .resultContent(resultContent)
                .build();
    }

    private static String detail(Object value, String kind, Object threshold) {
        JSONObject json = new JSONObject();
        json.put("kind", kind);
        json.put("value", String.valueOf(value));
        if (threshold != null) {
            json.put("threshold", String.valueOf(threshold));
        }
        return json.toJSONString();
    }

    /** 数值解析：非数值 displayValue 是数值规则的前置契约破坏，抛给 evaluate 隔离层（不猜不吞）。 */
    private static double parseDouble(String displayValue, AsmAlarmRuleDefinition rule, String uid, String attrId) {
        try {
            return Double.parseDouble(displayValue.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("数值规则收到非数值 displayValue: 规则=" + rule.getAlarmType()
                    + " uid=" + uid + " attrId=" + attrId + " value=" + displayValue, e);
        }
    }
}
