package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.UnitInfo;
import com.ecat.core.State.Unit.UnitInfoFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigUnitMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASM 每 series 单位契约解析器——按 (series, purpose) 解析目标单位，per-uid 缓存。
 *
 * <p>应用场景：读出口（P2 snapshot/SSE/history）与物化引擎（P1b）经本类取单位契约；配置端点或 seed
 * 直写 {@code asm_config_unit} 后调 {@link #invalidate} 失效缓存使下次解析重读 DB。</p>
 *
 * <p><b>per-uid 缓存</b>（同 ADM AdmStatContract 口径）：DB 查询粒度是 logic device
 * （{@code selectByLogicDevice(uid)} 一次返该设备全 attr 全 purpose 行），缓存键 = uid（查询粒度）。</p>
 *
 * <p><b>负结果不终身缓存</b>（ADM 教训）：uid 无任何配置行（设备刚建、seed 未跑）时<b>不写缓存</b>——
 * 否则后续 seed insertIfAbsent 落的行被旧负缓存屏蔽到重启。只有非空行集才入缓存；行集为空每次直查 DB
 * （量级：站房设备数 × 每分钟级解析，可忽略）。单 series 缺行（uid 有其他行但该 attr+purpose 无）是
 * 稳定语义（读出口显原生），随 uid 行集一起缓存合法。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmUnitContract {

    private final AsmConfigUnitMapper configUnitMapper;

    private final Log log = LogFactory.getLogger(getClass());

    /** per-uid 缓存：uid → (attrId → (purpose → 单位))。null 单位=该行存在但无量纲；缺 purpose 键=无该行（显原生）。 */
    private final ConcurrentHashMap<String, Map<String, EnumMap<AsmUnitPurpose, String>>> uidCache =
            new ConcurrentHashMap<>();

    /**
     * 解析 (series, purpose) 目标单位。
     *
     * @return 单位串（getFullUnitString key 形式）；可能为 null=无量纲（行存在 unit 列 NULL）或无该行（显原生）——
     *         两义由调用方按「无行→attr nativeUnit / 行 null→无量纲」区分时用 {@link #hasRow}
     */
    public String resolveUnit(AsmUnitPurpose purpose, String uid, String attrId) {
        EnumMap<AsmUnitPurpose, String> purposes = purposesOf(uid, attrId);
        return purposes.get(purpose);
    }

    /** 该 (series, purpose) 是否有配置行（区分「无行显原生」与「行存在但无量纲」）。 */
    public boolean hasRow(AsmUnitPurpose purpose, String uid, String attrId) {
        return purposesOf(uid, attrId).containsKey(purpose);
    }

    /**
     * 读出口统一单位解析 + 换算（P2：snapshot/历史两出口同走本方法，D4 单一换算口径）。
     *
     * <p>语义（与 ADM resolveDisplay + DisplayUnitConverter 同构）：</p>
     * <ol>
     *   <li><b>STORAGE / null purpose 严格抛</b>——STORAGE 是物化用途，掺读出口是调用错误（STANDARD=标准展示 / MONITOR=自定义展示 / HISTORY=历史自定义均合法）；</li>
     *   <li><b>缺行 → 显原生</b>：uid 无该 purpose 行（或行 unit 为空=无量纲）时值 + 源单位原样返
     *       （native 保底是设计语义非兜底）；</li>
     *   <li><b>换算</b>：源/目标同类（同 UnitInfo 枚举类）经 core {@code UnitInfo.convertUnit} 比率链
     *       （站房参数温度/电压/电流/百分比全走这条 SATP 通用换算链）；跨类站房域无任何可换算对
     *       （非气态无分子量），换算不可达 → 值 + 源单位原样显原生并 warn（清单见 P2 汇报）；</li>
     *   <li><b>脏 key 读侧宽松</b>：目标/源 key 解码失败（枚举重命名残留）→ 显原生 + warn，
     *       读路径不因脏单位配置 500（与写侧严格不对称，同 ADM 口径）。</li>
     * </ol>
     *
     * @param purpose       读出口用途（STANDARD/MONITOR/HISTORY；STORAGE/null 抛）
     * @param uid           站房逻辑设备 uniqueId
     * @param attrId        logic attr id
     * @param value         待换算数值（null → 原样 null 返，调用方渲染空）
     * @param sourceUnitKey 源单位 full key（stat 出口=asm_config_unit STORAGE 行 unit；snapshot=live
     *                      AttrState nativeUnit / raw 样本 unit 列；null=无单位无从换算，直通）
     * @return 换算结果（value + 实际单位 + converted 标记）
     * @throws IllegalArgumentException purpose 为 null 或 STORAGE
     */
    public AsmDisplayValue resolveDisplay(AsmUnitPurpose purpose, String uid, String attrId,
                                          Double value, String sourceUnitKey) {
        if (purpose == null) {
            throw new IllegalArgumentException("purpose 不能为 null（读出口只允许 STANDARD/MONITOR/HISTORY）");
        }
        if (purpose == AsmUnitPurpose.STORAGE) {
            throw new IllegalArgumentException(
                    "STORAGE 是物化用途，读出口只允许 STANDARD/MONITOR/HISTORY（uid=" + uid + " attrId=" + attrId + "）");
        }
        if (value == null) {
            return AsmDisplayValue.of(null, sourceUnitKey, false);
        }
        if (!hasRow(purpose, uid, attrId)) {
            return AsmDisplayValue.of(value, sourceUnitKey, false);
        }
        String targetKey = resolveUnit(purpose, uid, attrId);
        UnitInfo target = decodeLenient(targetKey, "目标");
        UnitInfo source = decodeLenient(sourceUnitKey, "源");
        if (target == null || source == null || target == source) {
            // 无偏好（行 null=无量纲）/ 源无单位 / 选回原生 → 直通显源
            return AsmDisplayValue.of(value, sourceUnitKey, false);
        }
        if (!target.getClass().equals(source.getClass())) {
            // 跨类：站房域非气态（无分子量）无任何跨类可换算对 → 显原生（设计语义，非猜测兜底）
            log.warn("[诊断调试] ASM 跨类单位换算不可达，显原生：uid={} attrId={} source={} target={}",
                    uid, attrId, sourceUnitKey, targetKey);
            return AsmDisplayValue.of(value, sourceUnitKey, false);
        }
        Double ratio = source.convertUnit(target);
        if (ratio == null) {
            log.warn("[诊断调试] ASM 单位换算比率缺失，显原生：uid={} attrId={} source={} target={}",
                    uid, attrId, sourceUnitKey, targetKey);
            return AsmDisplayValue.of(value, sourceUnitKey, false);
        }
        return AsmDisplayValue.of(value * ratio, targetKey, true);
    }

    /**
     * 单位 full key → 展示符号（总览页精修定案 4：出口不再输出 getFullUnitString 全串）。
     *
     * <p>如 {@code temperature.celsius} → {@code °C}、{@code voltage.volt} → {@code V}。
     * 解码失败（枚举重命名残留脏 key）→ 原样返 key（可见而非吞掉，读侧容错与 decodeLenient 同口径）。</p>
     *
     * @param fullKey 单位 full key（null/空 → null）
     * @return 单位符号；null=无量纲
     */
    public static String unitSymbol(String fullKey) {
        if (fullKey == null || fullKey.trim().isEmpty()) {
            return null;
        }
        try {
            UnitInfo unit = UnitInfoFactory.getEnum(fullKey);
            return unit != null ? unit.getName() : fullKey;
        } catch (IllegalArgumentException e) {
            return fullKey;
        }
    }

    /** 宽松解码（读侧容错）：null/空串/非法 key → null（调用方直通显原生）+ warn。 */
    private UnitInfo decodeLenient(String key, String role) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        try {
            return UnitInfoFactory.getEnum(key);
        } catch (IllegalArgumentException e) {
            log.warn("[诊断调试] ASM {}单位 key 解码失败，显原生：key={} 原因={}", role, key, e.getMessage());
            return null;
        }
    }

    /** 加载（或读缓存）uid 全行并取目标 attr 的 purpose 映射；uid 无行返空映射（负结果不缓存）。 */
    private EnumMap<AsmUnitPurpose, String> purposesOf(String uid, String attrId) {
        Map<String, EnumMap<AsmUnitPurpose, String>> byAttr = uidCache.get(uid);
        if (byAttr == null) {
            byAttr = loadUid(uid);
            if (byAttr.isEmpty()) {
                // 负结果不缓存：uid 无任何配置行（seed 未跑/设备刚建），直查——否则 seed 后落行被屏蔽到重启
                return new EnumMap<>(AsmUnitPurpose.class);
            }
            uidCache.put(uid, byAttr);
        }
        EnumMap<AsmUnitPurpose, String> purposes = byAttr.get(attrId);
        return purposes != null ? purposes : new EnumMap<>(AsmUnitPurpose.class);
    }

    /** selectByLogicDevice → (attrId → (purpose → unit))；空行集返空 Map（调用侧不缓存）。 */
    private Map<String, EnumMap<AsmUnitPurpose, String>> loadUid(String uid) {
        Map<String, EnumMap<AsmUnitPurpose, String>> byAttr = new ConcurrentHashMap<>();
        for (com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit row
                : configUnitMapper.selectByLogicDevice(uid)) {
            byAttr.computeIfAbsent(row.getAttrId(), k -> new EnumMap<>(AsmUnitPurpose.class))
                    .put(AsmUnitPurpose.of(row.getPurpose()), row.getUnit());
        }
        return byAttr;
    }

    /**
     * 失效某 series 缓存（配置端点/seed 直写 asm_config_unit 后调）。实际失效整个 uid
     * （缓存键=uid；该 uid 行集已变，全 attr 重读安全）。attrId 参数保留为 API 稳定（同 ADM 口径）。
     */
    public void invalidate(String uid, String attrId) {
        uidCache.remove(uid);
    }

    /** 失效全部缓存（全局性变更后调）。 */
    public void invalidateAll() {
        uidCache.clear();
    }
}
