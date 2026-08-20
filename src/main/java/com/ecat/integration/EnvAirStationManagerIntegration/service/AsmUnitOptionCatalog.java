package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.Unit.AirMassUnit;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmUnitOptionGroup;
import com.ecat.core.State.Unit.UnitInfoFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单位候选目录（单位设置抽屉）：按源单位枚举类产出「同类全部兄弟单位 + 气态跨类浓度单位」的分组候选，
 * 服务端按类缓存（snapshot 每数值行调用，避免每行重算枚举反射）。
 *
 * <p>同类判定 = UnitInfo 枚举类（与 {@link AsmUnitContract} 换算同类判定同口径）。气态跨类域 =
 * {@link AirMassUnit}（质量浓度）与 {@link AirVolumeUnit}（体积浓度 ppm/ppb）全集——站房域唯一可跨类
 * 换算对（经分子量换算）；源属其中之一时把另一个（或两个中的对方）类组追加在同类组之后。跨类目标选中后
 * 的换算行为走既有读出口语义（跨类不可达→显原生+warn），候选层不过滤、不预换算。</p>
 *
 * @author coffee
 */
public final class AsmUnitOptionCatalog {

    /** 单位类中文标签（未收录类回退枚举 simple name——可见而非吞掉）。 */
    private static final Map<Class<?>, String> CLASS_LABELS = new HashMap<>();

    /** 气态跨类域全集（质量浓度 ↔ 体积浓度）。 */
    private static final List<Class<?>> GASEOUS_CLASSES = Arrays.asList(
            AirMassUnit.class, AirVolumeUnit.class);

    static {
        CLASS_LABELS.put(AirMassUnit.class, "质量浓度");
        CLASS_LABELS.put(AirVolumeUnit.class, "体积浓度");
        CLASS_LABELS.put(com.ecat.core.State.Unit.TemperatureUnit.class, "温度");
        CLASS_LABELS.put(com.ecat.core.State.Unit.VoltageUnit.class, "电压");
        CLASS_LABELS.put(com.ecat.core.State.Unit.CurrentUnit.class, "电流");
        CLASS_LABELS.put(com.ecat.core.State.Unit.RatioUnit.class, "百分比");
        CLASS_LABELS.put(com.ecat.core.State.Unit.PressureUnit.class, "压力");
        CLASS_LABELS.put(com.ecat.core.State.Unit.SpeedUnit.class, "速度");
        CLASS_LABELS.put(com.ecat.core.State.Unit.FrequencyUnit.class, "频率");
        CLASS_LABELS.put(com.ecat.core.State.Unit.PowerUnit.class, "功率");
    }

    /** per-类候选缓存（静态不可变目录，进程生命周期有效）。 */
    private static final ConcurrentHashMap<Class<?>, List<AsmUnitOptionGroup>> CACHE =
            new ConcurrentHashMap<>();

    private AsmUnitOptionCatalog() {
    }

    /**
     * 产出源单位类的候选分组：同类组在前，源属气态域时其余气态类组按声明序追加。
     *
     * @param sourceUnitKey 源单位 full key（series native/storage 口径）；null/解码失败返 null（该行无单位
     *                      无从给同类候选，抽屉单位下拉空、只可改小数位）
     */
    public static List<AsmUnitOptionGroup> groupsFor(String sourceUnitKey) {
        if (sourceUnitKey == null || sourceUnitKey.trim().isEmpty()) {
            return null;
        }
        try {
            UnitInfo unit = UnitInfoFactory.getEnum(sourceUnitKey);
            return unit != null ? groupsFor(unit.getClass()) : null;
        } catch (IllegalArgumentException e) {
            // 脏 key（枚举重命名残留）：读侧宽松，无候选不阻塞快照
            return null;
        }
    }

    private static List<AsmUnitOptionGroup> groupsFor(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, c -> {
            List<AsmUnitOptionGroup> out = new ArrayList<>();
            out.add(groupOf(c));
            for (Class<?> gaseous : GASEOUS_CLASSES) {
                if (!gaseous.equals(c)) {
                    continue;
                }
                for (Class<?> other : GASEOUS_CLASSES) {
                    if (!other.equals(c)) {
                        out.add(groupOf(other));
                    }
                }
            }
            return out;
        });
    }

    private static AsmUnitOptionGroup groupOf(Class<?> clazz) {
        if (!clazz.isEnum() || !UnitInfo.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("非 UnitInfo 枚举类：" + clazz.getName());
        }
        List<AsmUnitOptionGroup.UnitOption> units = new ArrayList<>();
        for (Object c : clazz.getEnumConstants()) {
            UnitInfo u = (UnitInfo) c;
            units.add(new AsmUnitOptionGroup.UnitOption(u.getFullUnitString(), u.getName()));
        }
        String label = CLASS_LABELS.getOrDefault(clazz, clazz.getSimpleName());
        return new AsmUnitOptionGroup(label, units);
    }

    /** 测试用：清缓存。 */
    static void clearCache() {
        CACHE.clear();
    }

}
