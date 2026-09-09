package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigStatMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigUnitMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmGranularityMask;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmMaterializationMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatSeriesKindClassifier;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASM series 配置 seed 服务——首见 series（启动枚举 / consumer 数据事件）时 insertIfAbsent 默认配置行。
 *
 * <p><b>不监听设备生命周期事件</b>（ADM 教训）：设备存在性是用户驱动的运行时状态，任何时刻都保证不了
 * （启动时设备未建会空转，LOGIC_DEVICES_ALL_LOADED 事件同理不可靠）。seed 触发只有两路：
 * ①启动时经 LogicDeviceManager 枚举现存 airstation 逻辑设备预 seed 一轮（接线 P1b）；
 * ②consumer 首见某 series 的数据事件时补 seed（设备后建自然覆盖）。两路同走 {@link #seedSeries}。</p>
 *
 * <p><b>幂等</b>：DB 侧 ON CONFLICT DO NOTHING（已存在行——含人工配置/人工精化 unit——原样保留）；
 * 进程侧 seen set 去重（同 series 每 process 只发一次 insertIfAbsent，热路径零 DB）。</p>
 *
 * <p><b>准入判据</b>：numeric attr（AVG）或统计白名单非数值 attr（ALARM/STATE，2026-09-08 用户裁定
 * 放开）——判据收口在 {@link AsmStatSeriesKindClassifier#isSeedEligible}，与 consumer 首见路同源防漂移。</p>
 *
 * <p><b>默认值</b>：config_stat(enabled=true, mask=全开, BOTH) + config_unit(STORAGE 与 STANDARD 双行, native unit)。
 * native unit 取 airstation attr 定义（{@link AttributeBase#getNativeUnit()}，即 LogicAttributeDefine 注入值）；
 * 无单位写空串占位（行存在表达「已 seed」，读出口遇空串按显原生不换算；非数值 attr 恒空串=显无单位）。</p>
 *
 * <p><b>缓存失效</b>：直写 asm_config_unit 后必调 {@link AsmUnitContract#invalidate}（uid 级）——
 * 其负结果不终身缓存，seed 后下次解析即见新行。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmSeedService {

    /** seed 行的 created_by/updated_by 标记（审计区分 seed 默认与人工改）。 */
    public static final String SEED_ACTOR = "ASM";

    /** airstation 逻辑设备 uniqueId 前缀（编译期常量内联，不拖 AirstationIntegration 运行时依赖）。 */
    private static final String STATION_UID_PREFIX = "logicdevice_station.";

    private final Log log = LogFactory.getLogger(getClass());

    private final AsmConfigStatMapper configStatMapper;
    private final AsmConfigUnitMapper configUnitMapper;
    private final AsmUnitContract unitContract;

    /** 本进程已 seed 的 series（uid:attrId）——insertIfAbsent 进程级去重，热路径零 DB。 */
    private final Set<String> seededSeries = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * 启动预 seed：枚举传入的 airstation 逻辑设备（调用方 P1b 经 LogicDeviceManager 取全集），
     * 对每个统计准入 attr（numeric=AVG / 白名单非数值=ALARM、STATE）落默认配置行。
     * 非 airstation 设备（uid 前缀不符）跳过。
     *
     * @param devices 启动时刻 registry 现存逻辑设备全集（ ASM 只滤站房，其余前缀不动）
     * @return 实际 seed 的 series 数（含 seen 去重后跳过的）
     */
    public int seedStartup(Iterable<LogicDevice> devices) {
        if (devices == null) {
            throw new IllegalArgumentException("seedStartup devices 不可为 null");
        }
        int count = 0;
        for (LogicDevice device : devices) {
            String uid = device.getUniqueId();
            if (uid == null || !uid.startsWith(STATION_UID_PREFIX)) {
                continue;  // 非 airstation 站房设备（logicdevice.* 分析仪归 ADM），不碰
            }
            for (AttributeBase<?> attr : device.getAttrs().values()) {
                if (!AsmStatSeriesKindClassifier.isSeedEligible(attr)) {
                    continue;  // 白名单外非数值不 seed（计算属性/共享状态/命令/事件快照等，默认不统计）
                }
                if (seedSeries(uid, attr.getAttributeID(), nativeUnitOf(attr))) {
                    count++;
                }
            }
        }
        log.info("ASM 启动 seed 完成: {} series", count);
        return count;
    }

    /**
     * 单 series seed（consumer 首见数据事件 / 启动枚举共用）：config_stat + config_unit(STORAGE)
     * 双行 insertIfAbsent + 缓存失效。幂等——本进程已 seed 直接返 false 零 DB。
     *
     * @return true=本次实际落行；false=seen 去重跳过
     */
    public boolean seedSeries(String uid, String attrId, String nativeUnit) {
        if (uid == null || attrId == null || nativeUnit == null) {
            throw new IllegalArgumentException("seedSeries 入参不可为 null: uid=" + uid + " attrId=" + attrId);
        }
        String series = uid + ":" + attrId;
        if (!seededSeries.add(series)) {
            return false;
        }
        configStatMapper.insertIfAbsent(AsmConfigStat.builder()
                .logicDeviceUniqueId(uid)
                .attrId(attrId)
                .enabled(Boolean.TRUE)
                .granularityMask(AsmGranularityMask.ALL)
                .materializationMode(AsmMaterializationMode.BOTH.name())
                .build());
        configUnitMapper.insertIfAbsent(AsmConfigUnit.builder()
                .logicDeviceUniqueId(uid)
                .attrId(attrId)
                .purpose(AsmUnitPurpose.STORAGE.name())
                .unit(nativeUnit)
                .createdBy(SEED_ACTOR)
                .updatedBy(SEED_ACTOR)
                .build());
        // STANDARD 行（standard 模式读出口）：默认与 STORAGE 同源=attr nativeUnit，管理员可后续精化；
        // 对齐 ADM 双模式（standard 读 STANDARD 行 / custom 读 MONITOR 行）
        configUnitMapper.insertIfAbsent(AsmConfigUnit.builder()
                .logicDeviceUniqueId(uid)
                .attrId(attrId)
                .purpose(AsmUnitPurpose.STANDARD.name())
                .unit(nativeUnit)
                .createdBy(SEED_ACTOR)
                .updatedBy(SEED_ACTOR)
                .build());
        // 直写 asm_config_unit 后失效该 uid 缓存（负结果不终身缓存，下次解析即见 seed 行）
        unitContract.invalidate(uid, attrId);
        log.info("[诊断调试] ASM series 已 seed: {} (nativeUnit={})", series, nativeUnit);
        return true;
    }

    /** attr 定义 nativeUnit → getFullUnitString() key 形式；无单位（null）返空串占位（读出口显原生不换算）。 */
    private static String nativeUnitOf(AttributeBase<?> attr) {
        UnitInfo nativeUnit = attr.getNativeUnit();
        return nativeUnit == null ? "" : nativeUnit.getFullUnitString();
    }
}
