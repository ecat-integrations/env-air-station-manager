package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkAlarmEntry;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkAlarmTypeMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkControlResult;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.api.AsmParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkSnapshotAttr;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkStatRow;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmAlarmRecordRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmStatParamMetaRow;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleDefinition;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.core.State.AttributeBase;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AirStationSdk} 实现——外部集成读 ASM stat 聚合数据/站房当前态的出口。
 *
 * <p><b>与内部历史页同口径三保证</b>（外部上报与网页同数）：</p>
 * <ol>
 *   <li><b>batch 单 SQL</b>：N 参数一次 tuple IN 往返（interval_mode 过滤 + [start,end) + 升序），
 *       禁 N+1；无缓存直连 mapper。</li>
 *   <li><b>value=STORAGE 桶均值原值</b>（机对机口径不做展示换算；unit 恒为 value 实际单位，
 *       取 asm_config_unit STORAGE 行 full key）。非数值 series（ALARM/STATE）value=null、
 *       取 SdkStatRow.valueText 原样透传（unit=seed 空串占位=显无单位）。</li>
 *   <li><b>querySnapshot</b> 委派 {@link AsmSnapshotService}（live 优先/raw 回放兜，MONITOR 出口换算），
 *       REST snapshot 与 SDK 同一构建函数零口径分叉。</li>
 *   <li><b>queryAlarmEntries/listAlarmTypes</b>：按报警标识 + 左开右闭时间窗查 episode 重叠条目
 *       （含持续中），行形状与前端报警表格列一致；label 双字段与 durationMs 复用 REST 出口同一
 *       解析/计算函数（{@link AsmDeviceLabelService} / {@link AsmAlarmRecordRowDto#durationMs}）。</li>
 * </ol>
 *
 * <p>@Service 非 @Component：动态 jar 单例注册只认 @RestController/@Service。</p>
 *
 * @author coffee
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AirStationSdkImpl implements AirStationSdk {

    /** minute/5min 单次窗口上限（31 天，机对机批查防护）。 */
    static final Duration MAX_WINDOW_MINUTE_LEVEL = Duration.ofDays(31);

    /** hour 单次窗口上限（400 天）。 */
    static final Duration MAX_WINDOW_HOUR = Duration.ofDays(400);

    private final AsmHistoryQueryMapper historyMapper;
    private final AsmUnitContract unitContract;
    private final AsmSnapshotService snapshotService;
    private final AsmAlarmRecordMapper alarmRecordMapper;
    private final AsmDeviceLabelService labelService;
    private final AsmControlService controlService;

    @Override
    public List<SdkStatRow> queryStat(List<AsmParamKey> params, AsmStatGranularity granularity,
                                      AsmIntervalMode mode, Instant start, Instant end) {
        validate(params, granularity, mode, start, end);
        List<AsmHistoryParamKey> series = new ArrayList<>(params.size());
        for (AsmParamKey p : params) {
            series.add(AsmHistoryParamKey.of(p.getLogicDeviceUniqueId(), p.getAttrId()));
        }
        List<AsmHistoryBucket> rows = historyMapper.selectStatRows(
                granularity.targetTable(), start, end, series, mode.code(), 0, 0);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<SdkStatRow> out = new ArrayList<>(rows.size());
        for (AsmHistoryBucket row : rows) {
            out.add(SdkStatRow.builder()
                    .logicDeviceUniqueId(row.getLogicDeviceUniqueId())
                    .attrId(row.getAttrId())
                    .dataTime(row.getDataTime())
                    .value(row.getAvgValue())
                    .valueText(row.getValueText())
                    .validCount(row.getValidCount())
                    .totalCount(row.getTotalCount())
                    .unit(storageUnitOf(row.getLogicDeviceUniqueId(), row.getAttrId()))
                    .build());
        }
        return out;
    }

    @Override
    public List<SdkParamMeta> listStatParams() {
        List<AsmStatParamMetaRow> rows = historyMapper.selectStatParamMetas();
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        // displayName 解析按 uid 去重一次批量预取 live attrs（禁 per-row 查 registry 的 N+1）
        Map<String, Map<String, AttributeBase<?>>> attrsByUid = new HashMap<>();
        for (AsmStatParamMetaRow row : rows) {
            if (!attrsByUid.containsKey(row.getLogicDeviceUniqueId())) {
                attrsByUid.put(row.getLogicDeviceUniqueId(),
                        stationAttrsOf(row.getLogicDeviceUniqueId()));
            }
        }
        List<SdkParamMeta> out = new ArrayList<>(rows.size());
        for (AsmStatParamMetaRow row : rows) {
            AttributeBase<?> attr = attrsByUid.get(row.getLogicDeviceUniqueId()).get(row.getAttrId());
            out.add(SdkParamMeta.builder()
                    .logicDeviceUniqueId(row.getLogicDeviceUniqueId())
                    .attrId(row.getAttrId())
                    .paramDisplayName(displayNameOrAttrId(attr, row.getAttrId()))
                    .storageUnit(row.getUnit())
                    .applicableGranularityMask(row.getGranularityMask() != null
                            ? row.getGranularityMask() : 0)
                    .build());
        }
        return out;
    }

    @Override
    public List<SdkSnapshotAttr> querySnapshot(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            throw new IllegalArgumentException("uid 不能为空");
        }
        List<AsmSnapshotAttrDto> attrs = snapshotService.buildForUid(uid);
        List<SdkSnapshotAttr> out = new ArrayList<>(attrs.size());
        for (AsmSnapshotAttrDto a : attrs) {
            out.add(SdkSnapshotAttr.builder()
                    .attrId(a.getAttrId())
                    .value(a.getValue())
                    .valueText(a.getValueText())
                    .unit(a.getUnit())
                    .updateTime(a.getUpdateTime())
                    .source(a.getSource())
                    .build());
        }
        return out;
    }

    @Override
    public List<SdkAlarmEntry> queryAlarmEntries(String alarmType, Instant start, Instant end, int limit) {
        if (alarmType == null || alarmType.trim().isEmpty()) {
            throw new IllegalArgumentException("alarmType 不能为空（语义化报警标识，合法值可经 listAlarmTypes() 枚举）");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start/end 非 null");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start 必须早于 end: start=" + start + " end=" + end);
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 须在 1..1000: " + limit);
        }
        // 窗口语义（左开右闭 episode 重叠）由 mapper 承载，此处只委派——持续中 ACTIVE 行天然可查
        List<AsmAlarmRecord> rows = alarmRecordMapper.selectEntriesByType(alarmType, start, end, limit);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<SdkAlarmEntry> out = new ArrayList<>(rows.size());
        for (AsmAlarmRecord r : rows) {
            out.add(SdkAlarmEntry.builder()
                    .alarmType(r.getAlarmType())
                    .ruleName(r.getRuleName())
                    .logicDeviceUniqueId(r.getLogicDeviceUniqueId())
                    .deviceLabel(labelService.slotLabelOrNull(r.getLogicDeviceUniqueId()))
                    .attrId(r.getAttrId())
                    .attrLabel(labelService.attrLabelOrNull(r.getLogicDeviceUniqueId(), r.getAttrId()))
                    .severity(r.getSeverity())
                    .status(r.getStatus())
                    .triggerTime(r.getStartTime())
                    .recoverTime(r.getEndTime())
                    .durationMs(AsmAlarmRecordRowDto.durationMs(r))
                    .description(r.getDescription())
                    .build());
        }
        return out;
    }

    @Override
    public List<SdkAlarmTypeMeta> listAlarmTypes() {
        List<AsmAlarmRule> rows = alarmRecordMapper.selectTypeCatalog();
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<SdkAlarmTypeMeta> out = new ArrayList<>(rows.size());
        for (AsmAlarmRule row : rows) {
            try {
                // 规则定义解析承载 ruleName（setting_content.name）与合法性校验；坏行隔离同规则索引
                AsmAlarmRuleDefinition def = AsmAlarmRuleDefinition.parse(row);
                out.add(SdkAlarmTypeMeta.builder()
                        .alarmType(def.getAlarmType())
                        .ruleName(def.getName())
                        .severity(def.getSeverity())
                        .build());
            } catch (IllegalArgumentException e) {
                log.warn("asm_alarm_rule 坏行隔离跳过（目录只列合法标识）: alarmType={}, 原因={}",
                        row.getAlarmType(), e.getMessage());
            }
        }
        return out;
    }

    @Override
    public SdkControlResult control(String uid, String attrId, String value, String caller) {
        AsmControlRecord record = controlService.execute(AsmControlOrigin.LOCAL, caller, uid, attrId, value);
        return SdkControlResult.builder()
                .recordId(record.getId())
                .origin(record.getOrigin() == null ? null : record.getOrigin().name())
                .result(record.getResult() == null ? null : record.getResult().name())
                .error(record.getError())
                .durationMs(record.getDurationMs())
                .build();
    }

    /** STORAGE 行 unit（桶单位唯一真相源；行缺失=null 量纲语义）。 */
    private String storageUnitOf(String uid, String attrId) {
        return unitContract.resolveUnit(AsmUnitPurpose.STORAGE, uid, attrId);
    }

    /** uid → (attrId → live attr)；设备不在 registry 返空 Map（displayName 降级 attrId）。 */
    private static Map<String, AttributeBase<?>> stationAttrsOf(String uid) {
        for (LogicDevice device : LogicDeviceManager.getInstance().getRegisteredDevices()) {
            if (uid.equals(device.getUniqueId())) {
                return device.getAttrs();
            }
        }
        return Collections.emptyMap();
    }

    private static String displayNameOrAttrId(AttributeBase<?> attr, String attrId) {
        return attr != null && attr.getDisplayName() != null ? attr.getDisplayName() : attrId;
    }

    // ===== 入参校验（严格模式：任一不满足抛 IllegalArgumentException 明确告知，不静默兜底）=====

    private static void validate(List<AsmParamKey> params, AsmStatGranularity granularity,
                                 AsmIntervalMode mode, Instant start, Instant end) {
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("params 不能为空（至少一个 (logicDeviceUniqueId, attrId) 元组）");
        }
        if (granularity == null) {
            throw new IllegalArgumentException("granularity 不能为空（可选：MINUTE/FIVE_MIN/HOUR）");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空（可选：FRONT/BACK）");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start/end 不能为空");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start 须 < end（start=" + start + " end=" + end + "）");
        }
        Duration window = Duration.between(start, end);
        Duration limit = granularity == AsmStatGranularity.HOUR
                ? MAX_WINDOW_HOUR : MAX_WINDOW_MINUTE_LEVEL;
        if (window.compareTo(limit) > 0) {
            throw new IllegalArgumentException("时间窗口超限：粒度 " + granularity
                    + " 单次窗口上限 " + limit.toDays() + " 天（start=" + start + " end=" + end
                    + " 实际=" + window + "）");
        }
    }
}
