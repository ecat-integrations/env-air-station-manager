package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotAttrDto;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotDeviceDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 snapshot service——站房逻辑设备当前态构建（snapshot REST 与 {@code AirStationSdk.querySnapshot} 共用）。
 *
 * <p>无撕裂读契约（属性状态契约 §15）：每 attr 经 {@link AttributeBase#getState()} 一次性取不可变
 * {@link AttrState}，从 state 读 value/nativeUnit/lastUpdated——禁分别读 live attr 可变字段。
 * 值 + 单位经 {@link AsmUnitContract#resolveDisplay}(MONITOR) 统一出口换算（与 history 同口径）。</p>
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

    /** 值来源标记：LIVE=总线实时态 / RAW=raw 表最新值回放。 */
    static final String SOURCE_LIVE = "LIVE";
    static final String SOURCE_RAW = "RAW";

    private final AsmHistoryQueryMapper historyMapper;
    private final AsmUnitContract unitContract;

    /** 全部现存站房设备的当前态快照（registry 无站房设备时返空列表）。 */
    public List<AsmSnapshotDeviceDto> buildAll() {
        List<AsmSnapshotDeviceDto> out = new ArrayList<>();
        for (LogicDevice device : LogicDeviceManager.getInstance().getRegisteredDevices()) {
            String uid = device.getUniqueId();
            if (uid == null || !uid.startsWith(STATION_UID_PREFIX)) {
                continue;
            }
            out.add(AsmSnapshotDeviceDto.builder()
                    .logicDeviceUniqueId(uid)
                    .attrs(buildAttrs(device, uid))
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
        if (uid == null || uid.trim().isEmpty()) {
            throw new IllegalArgumentException("uid 不能为空");
        }
        for (LogicDevice device : LogicDeviceManager.getInstance().getRegisteredDevices()) {
            if (uid.equals(device.getUniqueId()) && uid.startsWith(STATION_UID_PREFIX)) {
                return buildAttrs(device, uid);
            }
        }
        return Collections.emptyList();
    }

    /** 单设备全属性：live state 优先，null/无值的 attr 用 raw 最新样本回放。 */
    private List<AsmSnapshotAttrDto> buildAttrs(LogicDevice device, String uid) {
        Map<String, AttributeBase<?>> attrs = device.getAttrs();
        // 首轮收集 live 缺席的 attrId（第二轮才查 raw——全 live 时零 raw SQL）
        List<String> missing = new ArrayList<>();
        Map<String, AsmSnapshotAttrDto> out = new HashMap<>();
        for (Map.Entry<String, AttributeBase<?>> e : attrs.entrySet()) {
            String attrId = e.getKey();
            AttrState<?> state = e.getValue().getState();
            if (state != null && state.getValue() != null) {
                out.put(attrId, liveRow(uid, attrId, state));
            } else {
                missing.add(attrId);
            }
        }
        if (!missing.isEmpty()) {
            for (AsmDataSample sample : rawLatest(uid)) {
                if (out.containsKey(sample.getAttrId())) {
                    continue;  // 已有 live 值（本路径理论不进，防御数据竞争窗口）
                }
                out.put(sample.getAttrId(), rawRow(uid, sample));
            }
        }
        List<AsmSnapshotAttrDto> rows = new ArrayList<>(out.values());
        rows.sort((a, b) -> a.getAttrId().compareTo(b.getAttrId()));
        return rows;
    }

    /** live 行：数值经 MONITOR 读出口换算，非数值走 displayValue 串（无单位概念）。 */
    private AsmSnapshotAttrDto liveRow(String uid, String attrId, AttrState<?> state) {
        Object value = state.getValue();
        if (value instanceof Number) {
            AsmDisplayValue display = unitContract.resolveDisplay(AsmUnitPurpose.MONITOR,
                    uid, attrId, ((Number) value).doubleValue(), unitKey(state.getNativeUnit()));
            return AsmSnapshotAttrDto.builder()
                    .attrId(attrId).value(display.getValue()).unit(display.getUnit())
                    .updateTime(state.getLastUpdated()).source(SOURCE_LIVE)
                    .build();
        }
        return AsmSnapshotAttrDto.builder()
                .attrId(attrId).valueText(state.getDisplayValue())
                .updateTime(state.getLastUpdated()).source(SOURCE_LIVE)
                .build();
    }

    /** raw 回放行：valueNum 数值经 MONITOR 出口换算（源=样本 unit 列），valueText 原样。 */
    private AsmSnapshotAttrDto rawRow(String uid, AsmDataSample sample) {
        if (sample.getValueNum() != null) {
            AsmDisplayValue display = unitContract.resolveDisplay(AsmUnitPurpose.MONITOR,
                    uid, sample.getAttrId(), sample.getValueNum().doubleValue(), sample.getUnit());
            return AsmSnapshotAttrDto.builder()
                    .attrId(sample.getAttrId()).value(display.getValue()).unit(display.getUnit())
                    .updateTime(sample.getDataTime()).source(SOURCE_RAW)
                    .build();
        }
        return AsmSnapshotAttrDto.builder()
                .attrId(sample.getAttrId()).valueText(sample.getValueText())
                .updateTime(sample.getDataTime()).source(SOURCE_RAW)
                .build();
    }

    private List<AsmDataSample> rawLatest(String uid) {
        List<AsmDataSample> rows = historyMapper.selectLatestSamples(
                Collections.singletonList(uid));
        return rows != null ? rows : Collections.<AsmDataSample>emptyList();
    }

    private static String unitKey(UnitInfo unit) {
        return unit != null ? unit.getFullUnitString() : null;
    }
}
