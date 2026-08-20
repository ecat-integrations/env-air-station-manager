package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigUnitMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmGranularityMask;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmMaterializationMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ASM 配置读写收口（REST 端点唯一入口）：
 * <ul>
 *   <li><b>config_stat</b>：读 selectAll / 写 upsert。写语义=「配置不回溯历史」——物化引擎下个 tick
 *       按新配置走，不触发重算/不回补历史（提示由 controller PUT 响应注明）。mask/mode 严格校验抛
 *       （{@link AsmGranularityMask#validate} / {@link AsmMaterializationMode#of}）。</li>
 *   <li><b>config_unit</b>：只读写 MONITOR/HISTORY 偏好行（STORAGE 行是 seed 域，端点拒绝改）。
 *       写后必调 {@link AsmUnitContract#invalidate}（直写不失效=负结果缓存终身教训）。</li>
 * </ul>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmConfigService {

    private final AsmConfigStatMapper configStatMapper;
    private final AsmConfigUnitMapper configUnitMapper;
    private final AsmUnitContract unitContract;

    /** 全部 series 聚合配置行（配置页表格数据源）。 */
    public List<AsmConfigStat> listStatConfig() {
        return configStatMapper.selectAll();
    }

    /**
     * 改单 series 聚合配置（enabled/mask/mode 全量覆盖 upsert）。
     *
     * @throws IllegalArgumentException series 空白 / mask 非法 / mode 非法
     */
    public AsmConfigStat updateStatConfig(String logicDeviceUniqueId, String attrId,
                                          Boolean enabled, Integer granularityMask,
                                          String materializationMode, String operator) {
        requireSeries(logicDeviceUniqueId, attrId);
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 必填（true/false）");
        }
        int mask = AsmGranularityMask.validate(granularityMask);
        String mode = AsmMaterializationMode.of(materializationMode).name();
        AsmConfigStat row = AsmConfigStat.builder()
                .logicDeviceUniqueId(logicDeviceUniqueId)
                .attrId(attrId)
                .enabled(enabled)
                .granularityMask(mask)
                .materializationMode(mode)
                .build();
        configStatMapper.upsert(row);
        return row;
    }

    /** MONITOR + HISTORY 偏好行（配置页单位偏好表数据源；STORAGE 不在端点域）。 */
    public List<AsmConfigUnit> listUnitPrefs() {
        List<AsmConfigUnit> out = new ArrayList<>();
        out.addAll(configUnitMapper.selectByPurpose(AsmUnitPurpose.MONITOR.name()));
        out.addAll(configUnitMapper.selectByPurpose(AsmUnitPurpose.HISTORY.name()));
        return out;
    }

    /**
     * 写单条单位偏好（MONITOR/HISTORY）。写后失效该 uid 单位缓存（读出口下次解析重读 DB）。
     *
     * <p>displayPrecision 可空：null=本次不改小数位（upsert coalesce 不覆盖已有值）；非 null 须在
     * 0-6 越界抛（监控页修约三级链的一级配置，仅 MONITOR 行语义生效）。</p>
     *
     * @throws IllegalArgumentException series 空白 / purpose 非法或为 STORAGE / displayPrecision 越界
     */
    public AsmConfigUnit updateUnitPref(String logicDeviceUniqueId, String attrId,
                                        String purpose, String unit, Integer displayPrecision,
                                        String operator) {
        requireSeries(logicDeviceUniqueId, attrId);
        if (displayPrecision != null && (displayPrecision < 0 || displayPrecision > 6)) {
            throw new IllegalArgumentException("displayPrecision 越界（0-6）：" + displayPrecision);
        }
        AsmUnitPurpose p = AsmUnitPurpose.of(purpose);
        if (p == AsmUnitPurpose.STORAGE) {
            throw new IllegalArgumentException("STORAGE 行由 seed 维护（存储单位换算源），配置端点只读写 MONITOR/HISTORY 偏好");
        }
        if (p == AsmUnitPurpose.STANDARD) {
            throw new IllegalArgumentException("STANDARD 行由 seed 维护（standard 模式标准口径），配置端点只读写 MONITOR/HISTORY 偏好");
        }
        AsmConfigUnit row = AsmConfigUnit.builder()
                .logicDeviceUniqueId(logicDeviceUniqueId)
                .attrId(attrId)
                .purpose(p.name())
                .unit(unit)
                .displayPrecision(displayPrecision)
                .createdBy(operator)
                .updatedBy(operator)
                .build();
        configUnitMapper.upsert(row);
        unitContract.invalidate(logicDeviceUniqueId, attrId);
        return row;
    }

    private static void requireSeries(String uid, String attrId) {
        if (uid == null || uid.trim().isEmpty() || attrId == null || attrId.trim().isEmpty()) {
            throw new IllegalArgumentException("logicDeviceUniqueId/attrId 必填");
        }
    }
}
