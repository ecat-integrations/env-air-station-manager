package com.ecat.integration.EnvAirStationManagerIntegration.rule;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析后的报警规则定义（setting_content JSON → 判定形态；解析失败抛 {@link IllegalArgumentException}，
 * 由 {@link AsmAlarmRuleIndex} 按坏配置隔离跳过——单条规则坏不毒死整个引擎）。
 *
 * <p>JSON 契约兼容 env_alarm_settings（device_info: uniqueId→[attrId]、name/enabled/configurable、
 * configs[type=range/number/duration/setting]），扩展字段：</p>
 * <ul>
 *   <li>{@code check}：显式判定类型（缺省按 configs 推断：range→RANGE_DURATION；number 且 class=power→POWER；
 *       number→INSTANT_THRESHOLD；无→STATUS_MATCH）；</li>
 *   <li>{@code compare}（number config）：gt（默认，超过触发）/lt（低于触发）；</li>
 *   <li>{@code match}（status config）：equals（默认）/contains。</li>
 * </ul>
 *
 * <p>修复点3：规则引用的设备/参数一律来自 device_info/configs，本类零硬编码 uniqueId/attrId。</p>
 *
 * @author coffee
 */
@Getter
public class AsmAlarmRuleDefinition {

    /** 状态串默认匹配集（站房域报警显示值约定，原 3/14 漏水设备报警等同语义）。 */
    static final List<String> DEFAULT_STATUS_VALUES =
            Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList("报警", "ON", "低报警", "高报警")));

    private final String alarmType;
    private final String name;
    private final String severity;
    private final boolean enabled;
    private final boolean configurable;
    private final Map<String, List<String>> devices;
    private final AsmAlarmCheckType check;
    private final Double rangeMin;
    private final Double rangeMax;
    private final Long durationMinutes;
    /** number config：attrId→阈值（class 对位 attrId，参数化）。 */
    private final Map<String, Double> thresholds;
    /** number config：attrId→比较方向（gt/lt，缺省 gt）。 */
    private final Map<String, String> compares;
    private final List<String> statusMatchValues;
    private final String statusMatchMode;
    /** P4 联动动作（configs setting 型：device_id/param_id/value 参数化；无则 null 不联动）。 */
    private final Linkage linkage;

    AsmAlarmRuleDefinition(String alarmType, String name, String severity, boolean enabled, boolean configurable,
                           Map<String, List<String>> devices, AsmAlarmCheckType check,
                           Double rangeMin, Double rangeMax, Long durationMinutes,
                           Map<String, Double> thresholds, Map<String, String> compares,
                           List<String> statusMatchValues, String statusMatchMode, Linkage linkage) {
        this.alarmType = alarmType;
        this.name = name;
        this.severity = severity;
        this.enabled = enabled;
        this.configurable = configurable;
        this.devices = devices;
        this.check = check;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.durationMinutes = durationMinutes;
        this.thresholds = thresholds;
        this.compares = compares;
        this.statusMatchValues = statusMatchValues;
        this.statusMatchMode = statusMatchMode;
        this.linkage = linkage;
    }

    /**
     * 报警联动动作（修复点3：联动设备 uid/attr/写值全参数化进规则 configs 的 setting 型配置，
     * 如 type=8 标气泄漏触发后开排风扇——原 env-alarm-manager 硬编码 fanDeviceId 改规则驱动）。
     */
    @Getter
    public static final class Linkage {
        private final String deviceUid;
        private final String attrId;
        private final String value;

        Linkage(String deviceUid, String attrId, String value) {
            this.deviceUid = deviceUid;
            this.attrId = attrId;
            this.value = value;
        }
    }

    /** 规则行 → 定义；坏配置（非法 JSON/缺 name/缺 device_info/severity 非法/range 缺 duration 等）抛 IAE。 */
    public static AsmAlarmRuleDefinition parse(AsmAlarmRule row) {
        if (row == null) {
            throw new IllegalArgumentException("报警规则行为 null");
        }
        if (row.getSettingContent() == null || row.getSettingContent().trim().isEmpty()) {
            throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " setting_content 为空");
        }
        JSONObject content;
        try {
            content = JSONObject.parse(row.getSettingContent());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "报警规则 " + row.getAlarmType() + " setting_content 非法 JSON: " + e.getMessage(), e);
        }
        String name = content.getString("name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " 缺 name");
        }
        JSONObject deviceInfo = content.getJSONObject("device_info");
        if (deviceInfo == null || deviceInfo.isEmpty()) {
            throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " 缺 device_info（规则参数化真相源）");
        }
        Map<String, List<String>> devices = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : deviceInfo.entrySet()) {
            String uid = e.getKey();
            List<String> attrs = new ArrayList<>();
            Object val = e.getValue();
            if (!(val instanceof JSONArray)) {
                throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " device_info[" + uid + "] 非数组");
            }
            for (Object a : (JSONArray) val) {
                attrs.add(String.valueOf(a));
            }
            if (attrs.isEmpty()) {
                throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " device_info[" + uid + "] 空参数列表");
            }
            devices.put(uid, Collections.unmodifiableList(attrs));
        }

        String severity = row.getSeverity() == null ? "0" : row.getSeverity();
        if (!"0".equals(severity) && !"1".equals(severity) && !"2".equals(severity)) {
            throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " severity 非法: " + severity
                    + "（合法 0/1/2）");
        }
        boolean enabled = Boolean.TRUE.equals(content.getBoolean("enabled"));
        boolean configurable = Boolean.TRUE.equals(content.getBoolean("configurable"));

        // configs 解析
        Double rangeMin = null;
        Double rangeMax = null;
        Long duration = null;
        Map<String, Double> thresholds = new LinkedHashMap<>();
        Map<String, String> compares = new LinkedHashMap<>();
        List<String> statusValues = null;
        String statusMode = "equals";
        Linkage linkage = null;
        JSONArray configs = content.getJSONArray("configs");
        if (configs != null) {
            for (int i = 0; i < configs.size(); i++) {
                JSONObject cfg = configs.getJSONObject(i);
                String type = cfg.getString("type");
                if ("range".equals(type)) {
                    JSONArray range = cfg.getJSONArray("value");
                    if (range == null || range.size() != 2) {
                        throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " range 配置非法（须 [min,max]）");
                    }
                    rangeMin = range.getDouble(0);
                    rangeMax = range.getDouble(1);
                } else if ("duration".equals(type)) {
                    duration = cfg.getLong("value");
                } else if ("number".equals(type)) {
                    String cls = cfg.getString("class");
                    if (cls == null || cls.trim().isEmpty()) {
                        throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " number 配置缺 class");
                    }
                    Double value = cfg.getDouble("value");
                    if (value == null) {
                        throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " number[" + cls + "] 缺 value");
                    }
                    thresholds.put(cls, value);
                    String compare = cfg.getString("compare");
                    compares.put(cls, compare == null ? "gt" : compare);
                } else if ("status".equals(type)) {
                    JSONArray values = cfg.getJSONArray("value");
                    if (values == null || values.isEmpty()) {
                        throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " status 配置缺 value 数组");
                    }
                    statusValues = new ArrayList<>();
                    for (Object v : values) {
                        statusValues.add(String.valueOf(v));
                    }
                    String match = cfg.getString("match");
                    if (match != null && !"equals".equals(match) && !"contains".equals(match)) {
                        throw new IllegalArgumentException("报警规则 " + row.getAlarmType() + " status match 非法: " + match);
                    }
                    statusMode = match == null ? "equals" : match;
                } else if ("setting".equals(type)) {
                    // P4 联动动作参数化（修复点3）：device_id/param_id/value 全须齐，缺任一按坏配置抛
                    String linkDevice = cfg.getString("device_id");
                    String linkAttr = cfg.getString("param_id");
                    String linkValue = cfg.getString("value");
                    if (linkDevice == null || linkAttr == null || linkValue == null) {
                        throw new IllegalArgumentException("报警规则 " + row.getAlarmType()
                                + " setting 联动配置须同时含 device_id/param_id/value");
                    }
                    linkage = new Linkage(linkDevice, linkAttr, linkValue);
                }
            }
        }

        AsmAlarmCheckType check = resolveCheck(content.getString("check"), rangeMin, thresholds, row.getAlarmType());
        if (check == AsmAlarmCheckType.RANGE_DURATION
                && (rangeMin == null || rangeMax == null || duration == null)) {
            throw new IllegalArgumentException("报警规则 " + row.getAlarmType()
                    + " RANGE_DURATION 须同时配置 range 与 duration");
        }

        List<String> effectiveStatusValues = statusValues == null ? DEFAULT_STATUS_VALUES : statusValues;
        return new AsmAlarmRuleDefinition(row.getAlarmType(), name, severity, enabled, configurable,
                Collections.unmodifiableMap(devices), check, rangeMin, rangeMax, duration,
                Collections.unmodifiableMap(thresholds), Collections.unmodifiableMap(compares),
                Collections.unmodifiableList(effectiveStatusValues), statusMode, linkage);
    }

    /** 显式 check 优先；缺省按 configs 推断（range→持续；number[class=power]→断电；number→瞬时；无→状态串）。 */
    private static AsmAlarmCheckType resolveCheck(String explicit, Double rangeMin,
                                                  Map<String, Double> thresholds, String alarmType) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return AsmAlarmCheckType.of(explicit);
        }
        if (rangeMin != null) {
            return AsmAlarmCheckType.RANGE_DURATION;
        }
        if (thresholds.containsKey("power")) {
            return AsmAlarmCheckType.POWER;
        }
        if (!thresholds.isEmpty()) {
            return AsmAlarmCheckType.INSTANT_THRESHOLD;
        }
        return AsmAlarmCheckType.STATUS_MATCH;
    }

    /** 瞬时阈值/断电阈值反查（attrId 参数化；无该 attr 的阈值返 null）。 */
    Double getThresholdFor(String attrId) {
        return thresholds.get(attrId);
    }

    /** 比较方向反查（无配置返 "gt" 默认）。 */
    String getCompareFor(String attrId) {
        String c = compares.get(attrId);
        return c == null ? "gt" : c;
    }
}
