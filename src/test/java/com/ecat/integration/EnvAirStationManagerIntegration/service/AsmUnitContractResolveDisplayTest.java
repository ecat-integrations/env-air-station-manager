package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.Unit.VoltageUnit;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * P2 读出口单位契约 resolveDisplay 行为测试（D4：存原生、读出口换算、缺行 native 保底是设计语义）。
 *
 * <p>可换算单位对用 VoltageUnit.VOLT ↔ MILLIVOLT（同类比值 1000，core UnitInfo.convertUnit 链）。</p>
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmUnitContractResolveDisplayTest {

    @Mock
    private AsmConfigUnitMapper configUnitMapper;

    private AsmUnitContract contract;

    private static final String UID = "logicdevice_station.demo";
    private static final String ATTR = "voltage";

    @BeforeEach
    void setUp() {
        contract = new AsmUnitContract(configUnitMapper);
    }

    private void mockRows(AsmConfigUnit... rows) {
        lenient().when(configUnitMapper.selectByLogicDevice(anyString()))
                .thenReturn(rows.length == 0 ? Collections.emptyList() : Arrays.asList(rows));
    }

    private static AsmConfigUnit row(AsmUnitPurpose purpose, String unit) {
        return AsmConfigUnit.builder()
                .logicDeviceUniqueId(UID).attrId(ATTR)
                .purpose(purpose.name()).unit(unit)
                .createdBy("t").updatedBy("t")
                .build();
    }

    @Test
    void storagePurposeMustThrowStrictly() {
        // STORAGE 是物化用途，掺读出口是配置错误——严格抛不猜测兜底
        assertThrows(IllegalArgumentException.class,
                () -> contract.resolveDisplay(AsmUnitPurpose.STORAGE, UID, ATTR, 1.0,
                        VoltageUnit.VOLT.getFullUnitString()));
    }

    @Test
    void nullPurposeMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> contract.resolveDisplay(null, UID, ATTR, 1.0, VoltageUnit.VOLT.getFullUnitString()));
    }

    @Test
    void missingRowMustFallBackToNative() {
        // uid 无任何行（seed 未跑）→ 值 + 原生单位原样（native 保底是设计语义非兜底）
        mockRows();
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.HISTORY, UID, ATTR, 220.0,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(Double.valueOf(220.0), v.getValue());
        assertEquals(VoltageUnit.VOLT.getFullUnitString(), v.getUnit());
        assertFalse(v.isConverted());
    }

    @Test
    void sameClassConvertiblePairMustConvert() {
        // HISTORY 行配 mV，源 V → 220V = 220000mV（同类比值 1000，core convertUnit）
        mockRows(row(AsmUnitPurpose.STORAGE, VoltageUnit.VOLT.getFullUnitString()),
                row(AsmUnitPurpose.HISTORY, VoltageUnit.MILLIVOLT.getFullUnitString()));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.HISTORY, UID, ATTR, 220.0,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(Double.valueOf(220000.0), v.getValue());
        assertEquals(VoltageUnit.MILLIVOLT.getFullUnitString(), v.getUnit());
        assertTrue(v.isConverted());
    }

    @Test
    void targetEqualsSourceMustPassThrough() {
        mockRows(row(AsmUnitPurpose.MONITOR, VoltageUnit.VOLT.getFullUnitString()));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.MONITOR, UID, ATTR, 220.0,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(Double.valueOf(220.0), v.getValue());
        assertFalse(v.isConverted());
    }

    @Test
    void crossClassNotConvertibleMustShowNative() {
        // 站房域无跨类可换算对（温度/电压/电流/% 全单类）→ 跨类目标 = 换算不可达 → 值+原单位原样显原生
        mockRows(row(AsmUnitPurpose.MONITOR, "TemperatureUnit.CELSIUS"));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.MONITOR, UID, ATTR, 220.0,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(Double.valueOf(220.0), v.getValue());
        assertEquals(VoltageUnit.VOLT.getFullUnitString(), v.getUnit());
        assertFalse(v.isConverted());
    }

    @Test
    void invalidTargetKeyMustFallBackToNative() {
        // 脏 key（单位枚举重命名残留）读路径容错：降级显原生不 500（读侧宽松，与写侧严格不对称）
        mockRows(row(AsmUnitPurpose.MONITOR, "NoSuchUnit.FOO"));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.MONITOR, UID, ATTR, 220.0,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(VoltageUnit.VOLT.getFullUnitString(), v.getUnit());
        assertFalse(v.isConverted());
    }

    @Test
    void nullValueMustReturnNullValueWithSourceUnit() {
        mockRows(row(AsmUnitPurpose.MONITOR, VoltageUnit.MILLIVOLT.getFullUnitString()));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.MONITOR, UID, ATTR, null,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(null, v.getValue());
        assertFalse(v.isConverted());
    }

    @Test
    void nullSourceUnitMustPassThrough() {
        // 源单位 null（STORAGE 行缺/无量纲）→ 无从换算 → 原样
        mockRows(row(AsmUnitPurpose.MONITOR, VoltageUnit.MILLIVOLT.getFullUnitString()));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.MONITOR, UID, ATTR, 220.0, null);
        assertEquals(Double.valueOf(220.0), v.getValue());
        assertFalse(v.isConverted());
    }

    @Test
    void standardPurposeMustResolveOwnRow() {
        // standard 模式读 STANDARD purpose 行（seed 默认=native，可被管理员精化），与 MONITOR 行互不串台
        mockRows(row(AsmUnitPurpose.STANDARD, VoltageUnit.VOLT.getFullUnitString()),
                row(AsmUnitPurpose.MONITOR, VoltageUnit.MILLIVOLT.getFullUnitString()));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.STANDARD, UID, ATTR, 220.0,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(Double.valueOf(220.0), v.getValue());
        assertEquals(VoltageUnit.VOLT.getFullUnitString(), v.getUnit());
        assertFalse(v.isConverted(), "STANDARD 行=V 源=V → 直通（MONITOR 行 mV 不影响 standard 出口）");
    }

    @Test
    void standardPurposeMissingRowMustFallBackToNative() {
        // STANDARD 行缺失（seed 未跑/未来新参数）→ native 保底（与 MONITOR 同口径）
        mockRows(row(AsmUnitPurpose.MONITOR, VoltageUnit.MILLIVOLT.getFullUnitString()));
        AsmDisplayValue v = contract.resolveDisplay(AsmUnitPurpose.STANDARD, UID, ATTR, 220.0,
                VoltageUnit.VOLT.getFullUnitString());
        assertEquals(Double.valueOf(220.0), v.getValue());
        assertEquals(VoltageUnit.VOLT.getFullUnitString(), v.getUnit());
        assertFalse(v.isConverted());
    }
}
