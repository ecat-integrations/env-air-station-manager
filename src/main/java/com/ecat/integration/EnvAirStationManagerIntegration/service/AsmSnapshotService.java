package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmOnlineJudge;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotDeviceDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmAlarmActiveDto;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.text.Collator;
import java.util.Locale;
import java.util.Comparator;

/**
 * P2 snapshot service——站房逻辑设备当前态构建（snapshot REST 与 {@code AirStationSdk.querySnapshot} 共用）。
 *
 * <p>无撕裂读契约（属性状态契约 §15）：每 attr 经 {@link AttributeBase#getState()} 一次性取不可变
 * {@link AttrState}，从 state 读 value/nativeUnit/lastUpdated——禁分别读 live attr 可变字段。
 * 值 + 单位经 {@link AsmUnitContract#resolveDisplay}（standard=STANDARD 行 / custom=MONITOR 行）统一出口换算。</p>
 *
 * <p><b>live 优先 / raw 回放兜</b>（设计 §4：snapshot 用 raw 最新值查询）：live state 为 null 或无值
 * （设备注册未喂数/重启后未到首帧）的 attr 用 {@code asm_data_sample} 每 series 最新样本回填
 * （source=RAW 标记），两路都有值时恒 LIVE。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmSnapshotService {

    /** airstation 逻辑设备 uniqueId 前缀（与 consumer/seed 同一判据）。 */
    static final String STATION_UID_PREFIX = "logicdevice_station.";

    /** 正常态选项 key（attr 侧判定用 value 选项 key，非 i18n 文案——文案变不漏判）。 */
    static final String ALARM_STATUS_NORMAL_KEY =
            com.ecat.integration.logicdevice.Meta.AttrIdDevice.SharedStatus.AlarmStatusOptions.NORMAL;

    /** 值来源标记：LIVE=总线实时态 / RAW=raw 表最新值回放 / DEF=仅有定义尚无任何值（占位行）。 */
    static final String SOURCE_LIVE = "LIVE";
    static final String SOURCE_RAW = "RAW";
    static final String SOURCE_DEF = "DEF";

    /**
     * 抽屉参数分组序（用户定案方案 B）：状态类(0) → 命令类(1) → 数值类(2)，组内 displayName 中文拼音序
     * （Collator zh），null 回退 attrId。瓦片核心参数按 catalog 顺序不受影响，本排序只用于抽屉/快照行序。
     *
     * <p>分组判据：命令类 = attrId 以 {@code _command} 结尾（airstation mapping 约定：reset_command /
     * calibrator_command / apply_manual_command 等）；数值类 = def.attrClassType 为 Number 子类；其余为
     * 状态类。DEF 占位行经 def 归入其分组，不单独垫底。</p>
     */
    static int attrGroup(String attrId, LogicAttributeDefine def) {
        if (attrId != null && attrId.endsWith("_command")) {
            return 1;
        }
        if (def != null && isNumericDef(def.getAttrClassType())) {
            return 2;
        }
        return 0;
    }

    /**
     * 数值 def 判定：attrClassType 在 mapping 侧是具体属性类（LNumericAttribute）或裸值类型
     * （Double/Integer，测试/SDK 构造形态）——两者任一是 Number 语义即数值类。
     */
    private static boolean isNumericDef(Class<?> attrClassType) {
        return attrClassType != null
                && (Number.class.isAssignableFrom(attrClassType)
                || com.ecat.integration.logicdevice.LogicState.LNumericAttribute.class.isAssignableFrom(attrClassType));
    }

    /**
     * 抽屉参数行全序：先分组序再组内拼音序。前端 drawerAttrs computed 用同 key 镜像（SSE patch 后不乱序）。
     */
    static List<AsmSnapshotAttrDto> sortAttrRows(List<AsmSnapshotAttrDto> rows,
                                                 Map<String, LogicAttributeDefine> defs) {
        rows.sort(Comparator
                .comparingInt((AsmSnapshotAttrDto r) -> attrGroup(r.getAttrId(), defs.get(r.getAttrId())))
                .thenComparing(ATTR_ORDER));
        return rows;
    }

    /**
     * 组内排序键：displayName 中文拼音序（Collator zh），null 回退 attrId。
     *
     * <p><b>多音字例外（用户定案）</b>：「重置*」的「重」按 <b>chóng</b> 排——Collator 默认读 zhòng 会把
     * 「重置」排到 z 段尾部，但命令组语义上重置类应排前，故 displayName 以「重置」开头的行组内强制最前。</p>
     */
    static final Comparator<AsmSnapshotAttrDto> ATTR_ORDER = (a, b) -> {
        boolean resetA = isResetCommand(a);
        boolean resetB = isResetCommand(b);
        if (resetA != resetB) {
            return resetA ? -1 : 1;
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        String ka = a.getDisplayName() != null ? a.getDisplayName() : a.getAttrId();
        String kb = b.getDisplayName() != null ? b.getDisplayName() : b.getAttrId();
        return collator.compare(ka, kb);
    };

    private static boolean isResetCommand(AsmSnapshotAttrDto row) {
        return row.getDisplayName() != null && row.getDisplayName().startsWith("重置");
    }

    /**
     * snapshot unit 模式 → 读出口 purpose（对齐 ADM unit gate）：{@code custom} 应用 MONITOR 偏好；
     * 其余（standard / 缺省 / 旧值）一律按 STANDARD 行（seed 默认=native）。与 ADM
     * {@code snapshot(unit)} 同口径；blank 视为非法输入显式抛（缺省语义只认 null）。
     */
    static AsmUnitPurpose purposeForUnit(String unit) {
        if (unit != null && unit.trim().isEmpty()) {
            throw new IllegalArgumentException("unit 参数为空白串（合法: standard/custom，缺省 null=standard）");
        }
        return "custom".equals(unit) ? AsmUnitPurpose.MONITOR : AsmUnitPurpose.STANDARD;
    }

    private final AsmHistoryQueryMapper historyMapper;
    private final AsmUnitContract unitContract;
    private final AsmAlarmRegistry alarmRegistry;

    /**
     * 全部现存站房设备的当前态快照（registry 无站房设备时返空列表）。
     *
     * <p><b>批量化（性能）</b>：DB 访问全部收敛为「一次单位契约批量预载 + 至多一次 raw 批量预取」，
     * 替代逐设备串行往返——站房 37 设备量级下，远程库逐 uid 往返（实测 30~140ms/次）曾把单次
     * snapshot 推到 1.4~7.5s。缺值设备占比 100% 时旧优化（anyMissing 短路）从不生效：每台站房设备
     * 都有至少一个「已定义但从未有值」的 attr（命令类/未绑定槽位），raw 预取退化为 37 连查。</p>
     *
     * @param unit 显示单位模式（standard=STANDARD 行标准口径 / custom=MONITOR 偏好；解析见 {@link #purposeForUnit}）
     */
    public List<AsmSnapshotDeviceDto> buildAll(String unit) {
        AsmUnitPurpose purpose = purposeForUnit(unit);
        List<LogicDevice> stations = new ArrayList<>();
        for (LogicDevice device : LogicDeviceManager.getInstance().getRegisteredDevices()) {
            String uid = device.getUniqueId();
            if (uid != null && uid.startsWith(STATION_UID_PREFIX)) {
                stations.add(device);
            }
        }
        // 单位契约批量预载：一次 IN 查询全部 uid（负结果也入缓存，杀逐 attr 直查税）
        List<String> uids = new ArrayList<>(stations.size());
        for (LogicDevice device : stations) {
            uids.add(device.getUniqueId());
        }
        unitContract.preload(uids);
        // 第一段：live 行收集（纯内存），同时标记缺值设备
        List<Draft> drafts = new ArrayList<>(stations.size());
        for (LogicDevice device : stations) {
            drafts.add(collectLive(device, purpose));
        }
        // 第二段：缺值设备 raw 最新样本一次 IN 批量预取（全 live 时零 raw SQL，语义同旧逐台短路）
        List<String> missingUids = new ArrayList<>();
        for (Draft d : drafts) {
            if (d.anyMissing) {
                missingUids.add(d.uid);
            }
        }
        Map<String, List<AsmDataSample>> rawByUid = missingUids.isEmpty()
                ? Collections.<String, List<AsmDataSample>>emptyMap()
                : rawLatestBatch(missingUids);
        // 第三段：raw 回放合并 + DEF 占位补齐 + 排序 + 设备 DTO 组装
        List<AsmSnapshotDeviceDto> out = new ArrayList<>(drafts.size());
        for (Draft d : drafts) {
            AsmOnlineJudge.Judgement online = AsmOnlineJudge.judge(d.device.getAttrs(), Instant.now());
            out.add(AsmSnapshotDeviceDto.builder()
                    .logicDeviceUniqueId(d.uid)
                    .displayName(d.device.getName())
                    .online(online.isOnline())
                    .offlineMs(online.getOfflineMs())
                    .attrs(finishRows(d, rawByUid.getOrDefault(d.uid, Collections.<AsmDataSample>emptyList()), purpose))
                    .activeAlarms(buildActiveAlarms(d.device, d.uid))
                    .build());
        }
        return out;
    }

    /**
     * 单设备当前态快照。
     *
     * @param uid 站房逻辑设备 uniqueId（非空非 blank）
     * @return 属性行列表；registry 无该设备（未建/前缀不符）返空列表（设备存在性=运行时状态，不抛）
     * @throws IllegalArgumentException uid null/blank
     */
    public List<AsmSnapshotAttrDto> buildForUid(String uid) {
        return buildForUid(uid, null);
    }

    /** 单设备当前态快照（unit 模式重载；SDK querySnapshot 缺省 null=standard）。 */
    public List<AsmSnapshotAttrDto> buildForUid(String uid, String unit) {
        if (uid == null || uid.trim().isEmpty()) {
            throw new IllegalArgumentException("uid 不能为空");
        }
        AsmUnitPurpose purpose = purposeForUnit(unit);
        for (LogicDevice device : LogicDeviceManager.getInstance().getRegisteredDevices()) {
            if (uid.equals(device.getUniqueId()) && uid.startsWith(STATION_UID_PREFIX)) {
                unitContract.preload(Collections.singletonList(uid));
                Draft d = collectLive(device, purpose);
                List<AsmDataSample> raw = d.anyMissing ? rawLatest(uid) : Collections.<AsmDataSample>emptyList();
                return finishRows(d, raw, purpose);
            }
        }
        return Collections.emptyList();
    }

    /**
     * 单设备行构建中间态：live 行已收集（纯内存），缺值标记待 raw 预取后由
     * {@link #finishRows} 合并——buildAll 批量预取与 buildForUid 单查共用同一三段式。
     */
    private static final class Draft {
        final LogicDevice device;
        final String uid;
        final Map<String, LogicAttributeDefine> defs;
        final Map<String, AsmSnapshotAttrDto> rows = new HashMap<>();
        boolean anyMissing;

        Draft(LogicDevice device, String uid, Map<String, LogicAttributeDefine> defs) {
            this.device = device;
            this.uid = uid;
            this.defs = defs;
        }
    }

    /**
     * 第一段：遍历 live attrs 收集有值行（live state 优先，撕裂读契约同前）；无值 attr 只置
     * missing 标记不落行（raw 回放/DEF 占位由 finishRows 决定）。
     */
    private Draft collectLive(LogicDevice device, AsmUnitPurpose purpose) {
        String uid = device.getUniqueId();
        Draft d = new Draft(device, uid, defIndex(device));
        for (Map.Entry<String, AttributeBase<?>> e : device.getAttrs().entrySet()) {
            String attrId = e.getKey();
            AttrState<?> state = e.getValue().getState();
            if (state != null && state.getValue() != null) {
                d.rows.put(attrId, liveRow(uid, attrId, state, d.defs.get(attrId), purpose));
            } else {
                d.anyMissing = true;
            }
        }
        return d;
    }

    /**
     * 第三段：raw 最新样本回放合并（live 已有的 attr 让位）→ 按 attr def 补占位行（已定义但
     * live/raw 均无值，如未绑定的 ai_running——瓦片 catalog 与抽屉同集，缺值行前端显 '-'，不隐行）
     * → 抽屉序排序。
     */
    private List<AsmSnapshotAttrDto> finishRows(Draft d, List<AsmDataSample> rawSamples, AsmUnitPurpose purpose) {
        for (AsmDataSample sample : rawSamples) {
            if (d.rows.containsKey(sample.getAttrId())) {
                continue;  // 已有 live 值（防御批量预取与 live 收集窗口间的数据竞争）
            }
            d.rows.put(sample.getAttrId(), rawRow(d.uid, sample, d.defs.get(sample.getAttrId()), purpose));
        }
        for (LogicAttributeDefine def : d.defs.values()) {
            if (!d.rows.containsKey(def.getAttrId())) {
                d.rows.put(def.getAttrId(), defRow(def));
            }
        }
        return sortAttrRows(new ArrayList<>(d.rows.values()), d.defs);
    }

    /** live 行：数值经 purpose 读出口换算 + HALF_EVEN 展示修约 + 单位符号化，非数值走 displayValue 串。 */
    private AsmSnapshotAttrDto liveRow(String uid, String attrId, AttrState<?> state, LogicAttributeDefine def, AsmUnitPurpose purpose) {
        String name = displayName(attrId, def);
        Object value = state.getValue();
        AttributeStatus status = state.getStatus();
        if (value instanceof Number) {
            String sourceKey = unitKey(state.getNativeUnit());
            AsmDisplayValue display = unitContract.resolveDisplay(purpose,
                    uid, attrId, ((Number) value).doubleValue(), sourceKey);
            return AsmSnapshotAttrDto.builder()
                    .attrId(attrId).displayName(name)
                    .value(AsmDisplayRounder.round(display.getValue(),
                            monitorPrecision(uid, attrId, def)))
                    .unit(AsmUnitContract.unitSymbol(display.getUnit()))
                    .unitKey(display.getUnit())
                    .displayPrecision(AsmDisplayRounder.resolvePrecision(
                            unitContract.monitorDisplayPrecision(uid, attrId), displayPrecision(def)))
                    .unitOptions(AsmUnitOptionCatalog.groupsFor(sourceKey))
                    .updateTime(state.getLastUpdated())
                    .statusName(status != null ? status.getDescription() : null)
                    .status(status != null ? status.name() : null)
                    .source(SOURCE_LIVE)
                    .attrGroup(attrGroup(attrId, def))
                    .build();
        }
        return AsmSnapshotAttrDto.builder()
                .attrId(attrId).displayName(name).valueText(state.getDisplayValue())
                .updateTime(state.getLastUpdated())
                .statusName(status != null ? status.getDescription() : null)
                .status(status != null ? status.name() : null)
                .source(SOURCE_LIVE)
                .attrGroup(attrGroup(attrId, def))
                .build();
    }

    /** raw 回放行：valueNum 数值经 purpose 出口换算 + 展示修约（源=样本 unit 列），valueText 原样。 */
    private AsmSnapshotAttrDto rawRow(String uid, AsmDataSample sample, LogicAttributeDefine def, AsmUnitPurpose purpose) {
        String name = displayName(sample.getAttrId(), def);
        if (sample.getValueNum() != null) {
            AsmDisplayValue display = unitContract.resolveDisplay(purpose,
                    uid, sample.getAttrId(), sample.getValueNum().doubleValue(), sample.getUnit());
            return AsmSnapshotAttrDto.builder()
                    .attrId(sample.getAttrId()).displayName(name)
                    .value(AsmDisplayRounder.round(display.getValue(),
                            monitorPrecision(uid, sample.getAttrId(), def)))
                    .unit(AsmUnitContract.unitSymbol(display.getUnit()))
                    .unitKey(display.getUnit())
                    .displayPrecision(AsmDisplayRounder.resolvePrecision(
                            unitContract.monitorDisplayPrecision(uid, sample.getAttrId()), displayPrecision(def)))
                    .unitOptions(AsmUnitOptionCatalog.groupsFor(sample.getUnit()))
                    .updateTime(sample.getDataTime()).source(SOURCE_RAW)
                    .attrGroup(attrGroup(sample.getAttrId(), def))
                    .build();
        }
        return AsmSnapshotAttrDto.builder()
                .attrId(sample.getAttrId()).displayName(name).valueText(sample.getValueText())
                .updateTime(sample.getDataTime()).source(SOURCE_RAW)
                .attrGroup(attrGroup(sample.getAttrId(), def))
                .build();
    }

    /**
     * 占位行：attr 已定义（mapping def）但 live/raw 均无值（如未绑定的 ai_running）。
     * value/valueText/updateTime/statusName 全 null——前端显 '-'，瓦片与抽屉同集同源。
     */
    private static AsmSnapshotAttrDto defRow(LogicAttributeDefine def) {
        return AsmSnapshotAttrDto.builder()
                .attrId(def.getAttrId())
                .displayName(def.getDisplayName())
                .source(SOURCE_DEF)
                .attrGroup(attrGroup(def.getAttrId(), def))
                .build();
    }

    /** attr def 索引（attrId → def）；def 是 displayName/展示精度的唯一来源。 */
    private static Map<String, LogicAttributeDefine> defIndex(LogicDevice device) {
        Map<String, LogicAttributeDefine> out = new HashMap<>();
        for (LogicAttributeDefine def : device.getAttrDefs()) {
            out.put(def.getAttrId(), def);
        }
        return out;
    }

    /** 参数中文名：def displayName 回退 attrId。 */
    private static String displayName(String attrId, LogicAttributeDefine def) {
        return def != null ? def.getDisplayName() : attrId;
    }

    /** 展示精度：def displayPrecision，缺席/非法走默认（AsmDisplayRounder 兜）。 */
    private static Integer displayPrecision(LogicAttributeDefine def) {
        return def != null ? def.getDisplayPrecision() : null;
    }

    /** 监控页修约精度三级链：MONITOR 行 display_precision → def displayPrecision → 默认 2。 */
    private Integer monitorPrecision(String uid, String attrId, LogicAttributeDefine def) {
        return AsmDisplayRounder.resolvePrecision(
                unitContract.monitorDisplayPrecision(uid, attrId), displayPrecision(def));
    }

    /**
     * 设备级活跃报警（两源并集，P2）：
     * <ul>
     *   <li><b>registry 侧</b>：规则引擎活跃 episode（sweep 闭单摘槽，徽章消退靠 sweep 周期/下次进页
     *       对齐——已知滞后边界，ADM 同款取舍）；</li>
     *   <li><b>attr 侧</b>：设备自报 {@code alarm_status} 聚合值非「正常」即报警（attr 侧报警态随采样
     *       自动消退）。</li>
     * </ul>
     * 按 attrId+alarmType 粗粒度去重（两源口径不同不算重复）。
     */
    private List<AsmAlarmActiveDto> buildActiveAlarms(LogicDevice device, String uid) {
        Map<String, LogicAttributeDefine> defs = defIndex(device);
        Map<String, AsmAlarmActiveDto> out = new HashMap<>();
        for (AsmAlarmRegistry.ActiveAlarm alarm : alarmRegistry.activeByUid(uid)) {
            String attrId = alarm.getAttrId();
            out.putIfAbsent(attrId + "|" + alarm.getAlarmType(), AsmAlarmActiveDto.builder()
                    .attrId(attrId)
                    .displayName(displayName(attrId, defs.get(attrId)))
                    .alarmType(alarm.getAlarmType())
                    .ruleName(alarm.getRuleName())
                    .startTime(alarm.getStartTime())
                    .build());
        }
        for (LogicAttributeDefine def : defs.values()) {
            if (def.getAttrClass() != AttributeClass.ALARM_STATUS) {
                continue;
            }
            AttributeBase<?> attr = device.getAttrs().get(def.getAttrId());
            if (attr == null) {
                continue;
            }
            AttrState<?> state = attr.getState();
            // 判定用 value 选项 key（"normal"/"alarm"，locale 无关）；displayValue 只作展示文案。
            if (state == null || !(state.getValue() instanceof String)
                    || ALARM_STATUS_NORMAL_KEY.equals(state.getValue())) {
                continue;
            }
            out.putIfAbsent(def.getAttrId() + "|" + AsmAlarmActiveDto.TYPE_ATTR_STATUS,
                    AsmAlarmActiveDto.builder()
                            .attrId(def.getAttrId())
                            .displayName(displayName(def.getAttrId(), def))
                            .alarmType(AsmAlarmActiveDto.TYPE_ATTR_STATUS)
                            .ruleName(state.getDisplayValue())
                            .startTime(state.getLastUpdated())
                            .build());
        }
        return new ArrayList<>(out.values());
    }

    private List<AsmDataSample> rawLatest(String uid) {
        List<AsmDataSample> rows = historyMapper.selectLatestSamples(
                Collections.singletonList(uid));
        return rows != null ? rows : Collections.<AsmDataSample>emptyList();
    }

    /** 批量 raw 最新样本：一次 IN 查询（全部缺值 uid）结果按 uid 分组回各设备；null 行集按空处理。 */
    private Map<String, List<AsmDataSample>> rawLatestBatch(List<String> uids) {
        List<AsmDataSample> rows = historyMapper.selectLatestSamples(uids);
        Map<String, List<AsmDataSample>> byUid = new HashMap<>();
        if (rows != null) {
            for (AsmDataSample sample : rows) {
                byUid.computeIfAbsent(sample.getLogicDeviceUniqueId(), k -> new ArrayList<>()).add(sample);
            }
        }
        return byUid;
    }

    private static String unitKey(UnitInfo unit) {
        return unit != null ? unit.getFullUnitString() : null;
    }
}
