package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmConfigUnitMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AsmUnitContract 单测——per-uid 缓存命中 / 负结果不终身缓存（空行集不入缓存，seed 后立即可见）/
 * invalidate 失效重读。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmUnitContractTest {

    @Mock
    private AsmConfigUnitMapper mapper;

    private AsmUnitContract contract;

    @BeforeEach
    void setUp() {
        contract = new AsmUnitContract(mapper);
    }

    private static AsmConfigUnit row(String uid, String attrId, String purpose, String unit) {
        return AsmConfigUnit.builder()
                .logicDeviceUniqueId(uid).attrId(attrId).purpose(purpose).unit(unit).build();
    }

    @Test
    void resolveUnit_positiveRows_cachedPerUid() {
        when(mapper.selectByLogicDevice("u")).thenReturn(Arrays.asList(
                row("u", "temperature", "STORAGE", "TemperatureUnit.CELSIUS"),
                row("u", "temperature", "MONITOR", "TemperatureUnit.FAHRENHEIT")));

        assertEquals("TemperatureUnit.CELSIUS",
                contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "temperature"));
        assertEquals("TemperatureUnit.FAHRENHEIT",
                contract.resolveUnit(AsmUnitPurpose.MONITOR, "u", "temperature"));
        // 再解析：per-uid 缓存命中，只查一次 DB
        contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "temperature");
        verify(mapper, times(1)).selectByLogicDevice("u");
    }

    @Test
    void resolveUnit_missingPurposeRow_isNativeFallbackSemantics() {
        when(mapper.selectByLogicDevice("u")).thenReturn(Collections.singletonList(
                row("u", "temperature", "STORAGE", "TemperatureUnit.CELSIUS")));

        // uid 有行但 MONITOR 无行：稳定负语义，随 uid 行集缓存（hasRow 区分「无行」与「行 unit NULL」）
        assertFalse(contract.hasRow(AsmUnitPurpose.MONITOR, "u", "temperature"));
        assertTrue(contract.hasRow(AsmUnitPurpose.STORAGE, "u", "temperature"));
        assertEquals(null, contract.resolveUnit(AsmUnitPurpose.MONITOR, "u", "temperature"));
    }

    @Test
    void negativeResult_notCachedForever_seedBecomesVisibleImmediately() {
        // 第一次：uid 无任何行（seed 未跑）——负结果不缓存
        when(mapper.selectByLogicDevice("u")).thenReturn(Collections.emptyList());
        assertEquals(null, contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "a"));

        // 第二次：seed 已落行——无旧负缓存屏蔽，直查 DB 立即可见
        when(mapper.selectByLogicDevice("u")).thenReturn(Collections.singletonList(
                row("u", "a", "STORAGE", "TemperatureUnit.CELSIUS")));
        assertEquals("TemperatureUnit.CELSIUS",
                contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "a"));
        verify(mapper, times(2)).selectByLogicDevice("u");
    }

    @Test
    void invalidate_forcesReload() {
        when(mapper.selectByLogicDevice("u")).thenReturn(Collections.singletonList(
                row("u", "a", "STORAGE", "TemperatureUnit.CELSIUS")));
        contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "a");

        // 配置端点直写后失效 → 重读拿到新值
        when(mapper.selectByLogicDevice("u")).thenReturn(Collections.singletonList(
                row("u", "a", "STORAGE", "NoConversionUnit.Degree")));
        contract.invalidate("u", "a");
        assertEquals("NoConversionUnit.Degree",
                contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "a"));
        verify(mapper, times(2)).selectByLogicDevice("u");
    }

    @Test
    void invalidateAll_forcesReload() {
        when(mapper.selectByLogicDevice("u")).thenReturn(Collections.singletonList(
                row("u", "a", "STORAGE", "TemperatureUnit.CELSIUS")));
        contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "a");

        when(mapper.selectByLogicDevice("u")).thenReturn(Collections.singletonList(
                row("u", "a", "STORAGE", "NoConversionUnit.Degree")));
        contract.invalidateAll();
        assertEquals("NoConversionUnit.Degree",
                contract.resolveUnit(AsmUnitPurpose.STORAGE, "u", "a"));
        verify(mapper, times(2)).selectByLogicDevice("u");
    }

    // ===== 批量预载（snapshot 请求开头一次 IN 查询）=====

    @Test
    void preload_negativeUidAlsoCached_untilInvalidated() {
        // u1 有行、u2 无行（如 camera/阀组等非数值设备，天然无单位偏好）——一次 IN 查询后两 uid 全入缓存。
        // 批量口径下负结果也入缓存（否则负 uid 每个数值 attr 仍逐次直查 DB），正确性前提 =
        // 全部写路径（配置端点/seed）直写后都调 invalidate；invalidate 后 seed 落行立即可见（下段验证）。
        when(mapper.selectByLogicDevices(anyCollection())).thenReturn(Collections.singletonList(
                row("u1", "a", "STORAGE", "TemperatureUnit.CELSIUS")));
        contract.preload(Arrays.asList("u1", "u2"));

        assertTrue(contract.hasRow(AsmUnitPurpose.STORAGE, "u1", "a"));
        assertFalse(contract.hasRow(AsmUnitPurpose.STORAGE, "u2", "a"));
        verify(mapper, never()).selectByLogicDevice(anyString());

        // seed 落行 + invalidate → 负缓存失效，直查 DB 立即可见（不屏蔽到重启）
        when(mapper.selectByLogicDevice("u2")).thenReturn(Collections.singletonList(
                row("u2", "a", "STORAGE", "TemperatureUnit.CELSIUS")));
        contract.invalidate("u2", "a");
        assertTrue(contract.hasRow(AsmUnitPurpose.STORAGE, "u2", "a"));
    }

    @Test
    void preload_skipsCachedUids_andShortCircuitsEmptyInput() {
        when(mapper.selectByLogicDevices(anyCollection())).thenReturn(Collections.singletonList(
                row("u1", "a", "STORAGE", "TemperatureUnit.CELSIUS")));
        contract.preload(Collections.singletonList("u1"));

        // 已缓存 uid 不再进批量查询（第二次只查 u2）；空入参零 DB
        when(mapper.selectByLogicDevices(anyCollection())).thenReturn(Collections.emptyList());
        contract.preload(Arrays.asList("u1", "u2"));
        contract.preload(Collections.emptyList());
        contract.preload(null);
        verify(mapper, times(2)).selectByLogicDevices(anyCollection());
    }

    // ===== 单位 full key → 人类显示串（历史页 display_unit 契约源）=====

    @Test
    void unitDisplayName_resolvesI18nSymbol() {
        // 源=core UnitInfo.getDisplayName()（InternationalizedUnit → core strings.json）：
        // TemperatureUnit.CELSIUS → state.unit.temperature.celsius → °C
        assertEquals("°C", AsmUnitContract.unitDisplayName("TemperatureUnit.CELSIUS"));
        assertEquals("mV", AsmUnitContract.unitDisplayName("VoltageUnit.MILLIVOLT"));
    }

    @Test
    void unitDisplayName_nullOrEmptyYieldsNull_dimensionlessSemantics() {
        assertEquals(null, AsmUnitContract.unitDisplayName(null));
        assertEquals(null, AsmUnitContract.unitDisplayName("  "));
    }

    @Test
    void unitDisplayName_undecodableKeyFallsBackToOriginalText() {
        // 脏 key（枚举重命名残留）→ 回退原文可见，不吞不猜
        assertEquals("NotAnUnit.X", AsmUnitContract.unitDisplayName("NotAnUnit.X"));
    }

    // ===== MONITOR 行 display_precision 供给（监控页修约三级链一级）=====

    @Test
    void monitorDisplayPrecision_fromMonitorRowAndCachedWithUnitRowSet() {
        when(mapper.selectByLogicDevice("u")).thenReturn(Arrays.asList(
                row("u", "temperature", "MONITOR", "TemperatureUnit.CELSIUS")));

        AsmConfigUnit withPrecision = AsmConfigUnit.builder()
                .logicDeviceUniqueId("u").attrId("co").purpose("MONITOR")
                .unit("AirMassUnit.MGM3").displayPrecision(4).build();
        when(mapper.selectByLogicDevice("u2")).thenReturn(Collections.singletonList(withPrecision));

        assertEquals(null, contract.monitorDisplayPrecision("u", "temperature"));
        assertEquals(Integer.valueOf(4), contract.monitorDisplayPrecision("u2", "co"));
    }
}
