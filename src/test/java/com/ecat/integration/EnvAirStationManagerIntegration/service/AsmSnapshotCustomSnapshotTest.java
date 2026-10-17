package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.BusTopic;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.Bus.event.DeviceLifecycleEvent;
import com.ecat.core.Bus.event.EventContext;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkCustomSnapshotAttr;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkCustomSnapshotProjection;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkCustomSnapshotRequest;
import com.ecat.integration.logicdevice.Meta.AttrIdStation.AccessControl;
import com.ecat.integration.logicdevice.Meta.AttrIdStation.FilterChanger;
import com.ecat.integration.logicdevice.Meta.AttrIdStation.StandardGas;
import com.ecat.integration.logicdevice.Meta.AttrIdStation.StatusOptions;
import com.ecat.integration.logicdevice.Meta.DeviceId;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * caller-defined live snapshot 严格契约测试：LD 原值为数值唯一源、请求精度唯一精度源、
 * LD displayValue 仅服务展示型非数值行；任何单位/精度/类型契约错误都同步失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsmSnapshotCustomSnapshotTest {

    private static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
    private static final String UID = DeviceId.Station.STANDARD_GAS_SO2;
    private static final String PRESSURE_ATTR = StandardGas.GAS_PRESSURE_REMAINING;
    private static final String TEXT_ATTR = StandardGas.CYLINDER_ID;
    private static final String SELECT_ATTR = StandardGas.GAS_LEAK_ALARM;
    private static final String BINARY_ATTR = AccessControl.LOCK_STATUS;
    private static final String TIME_ATTR = FilterChanger.LAST_CHANGE_TIME;
    private static final String I18N_ATTR = StandardGas.GAS_SOURCE;
    private static final String TEST_DIMENSIONLESS_ATTR = "test_dimensionless_fixture";
    private static final String TEST_TIE_ATTR = "test_half_even_tie_fixture";
    private static final String COMMAND_ATTR = StandardGas.ACTIVATE_COMMAND;
    private static final String TEST_UNKNOWN_ATTR = "test_unknown_fixture";

    @Mock
    private AsmUnitContract unitContract;

    private AsmSnapshotService service;
    private DeviceRegistry deviceRegistry;
    private BusRegistry busRegistry;

    @BeforeEach
    void setUp() {
        service = new AsmSnapshotService(unitContract, new AsmAlarmRegistry());
        deviceRegistry = new DeviceRegistry();
        busRegistry = new BusRegistry();
        LogicDeviceManager manager = LogicDeviceManager.getInstance();
        manager.reset();
        manager.setRegistry(deviceRegistry);
        manager.setBusRegistry(busRegistry);

        // 显式布置会被旧出口读取的 ASM 偏好；新出口必须完全绕开。
        lenient().when(unitContract.monitorDisplayPrecision(UID, PRESSURE_ATTR)).thenReturn(0);
        lenient().when(unitContract.resolveDisplay(any(AsmUnitPurpose.class), anyString(), anyString(), anyDouble(), anyString()))
                .thenReturn(AsmDisplayValue.of(999.0D, VoltageUnit.VOLT.getFullUnitString(), true));
    }

    @AfterEach
    void tearDown() {
        LogicDeviceManager.getInstance().reset();
    }

    @Test
    void numericConversionUsesRequestedUnitAndPrecision_notAsmPreferenceOrDisplayValue() {
        registerDevice(pressureState("123.457", 123.45679D, PressureUnit.KPA));
        SdkCustomSnapshotAttr row = service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, PressureUnit.MPA.getFullUnitString(), 3))).get(0);

        assertEquals(PRESSURE_ATTR, row.getAttrId());
        assertEquals(0.123D, row.getValue());
        assertEquals("0.123", row.getValueText());
        assertEquals(PressureUnit.MPA.getFullUnitString(), row.getUnit().getFullName());
        assertEquals(PressureUnit.MPA.getName(), row.getUnit().getSymbol());
        assertEquals(PressureUnit.MPA.getDisplayName(), row.getUnit().getDisplayName());
        assertEquals(3, row.getDisplayPrecision());
        assertEquals("LIVE", row.getSource());
        assertEquals(T0, row.getUpdateTime());
        assertEquals(AttributeStatus.ZERO_CHECK.name(), row.getStatus().getKey());
        assertEquals(AttributeStatus.ZERO_CHECK.getDescription(), row.getStatus().getDisplayName());
        verify(unitContract, never()).monitorDisplayPrecision(anyString(), anyString());
        verify(unitContract, never()).resolveDisplay(any(AsmUnitPurpose.class), anyString(), anyString(), anyDouble(), anyString());
    }

    @Test
    void numericNativeProjectionUsesAttrStateValue_notDisplayValue() {
        registerDevice(pressureState("123.457", 123.456789D, PressureUnit.KPA));
        SdkCustomSnapshotAttr row = service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, "", 6))).get(0);

        assertEquals(123.456789D, row.getValue());
        assertEquals("123.456789", row.getValueText());
        assertEquals(PressureUnit.KPA.getFullUnitString(), row.getUnit().getFullName());
        assertEquals(6, row.getDisplayPrecision());
        verify(unitContract, never()).monitorDisplayPrecision(anyString(), anyString());
        verify(unitContract, never()).resolveDisplay(any(AsmUnitPurpose.class), anyString(), anyString(), anyDouble(), anyString());
    }

    @Test
    void dimensionlessNumericProjectionReturnsNullUnitWithValueAndFixedText() {
        registerDevice(dimensionlessState("1.23456", 1.23456D));
        SdkCustomSnapshotAttr row = service.queryCustomSnapshot(request(
                projection(TEST_DIMENSIONLESS_ATTR, "", 3))).get(0);

        assertEquals(1.235D, row.getValue());
        assertEquals("1.235", row.getValueText());
        assertNull(row.getUnit());
        assertEquals(3, row.getDisplayPrecision());
    }

    @Test
    void halfEvenAndTrailingZerosUseRequestedPrecisionOnly() {
        registerDevice(
                dimensionlessState("0.120456", 0.120456D),
                dimensionlessState(TEST_TIE_ATTR, "0.1245", 0.1245D));
        List<SdkCustomSnapshotAttr> rows = service.queryCustomSnapshot(request(
                projection(TEST_DIMENSIONLESS_ATTR, "", 3),
                projection(TEST_TIE_ATTR, "", 3)));
        assertEquals("0.120", rows.get(0).getValueText());
        assertEquals(0.120D, rows.get(0).getValue());
        assertEquals(3, rows.get(0).getDisplayPrecision());
        assertNull(rows.get(0).getUnit());
        assertEquals("0.124", rows.get(1).getValueText());
    }

    @Test
    void zeroPrecisionProducesPlainIntegerTextAndHalfEvenTie() {
        registerDevice(
                dimensionlessState(TEST_DIMENSIONLESS_ATTR, "2.5", 2.5D),
                dimensionlessState(TEST_TIE_ATTR, "odd_tie", 3.5D));
        List<SdkCustomSnapshotAttr> rows = service.queryCustomSnapshot(request(
                projection(TEST_DIMENSIONLESS_ATTR, "", 0),
                projection(TEST_TIE_ATTR, "", 0)));
        assertEquals("2", rows.get(0).getValueText());
        assertEquals(2.0D, rows.get(0).getValue());
        assertEquals("4", rows.get(1).getValueText());
        assertEquals(4.0D, rows.get(1).getValue());
    }

    @Test
    void dimensionlessNumericWithExplicitTargetFailsFast() {
        registerDevice(dimensionlessState("1.0", 1.0D));
        assertThrows(IllegalStateException.class, () -> service.queryCustomSnapshot(request(
                projection(TEST_DIMENSIONLESS_ATTR, PressureUnit.MPA.getFullUnitString(), 3))));
    }

    @Test
    void displayAttributesProjectStateDisplayValue_withStrictEmptyUnitAndNullPrecision() {
        registerDevice(
                textState(TEXT_ATTR, "camera_on", "摄像机开启"),
                selectState(SELECT_ATTR, "on", "运行"),
                binaryState(BINARY_ATTR, true, "开启"),
                timeState(TIME_ATTR, T0, "00:00:00"),
                textState(I18N_ATTR, "normal", "正常"));

        List<SdkCustomSnapshotAttr> rows = service.queryCustomSnapshot(request(
                projection(TEXT_ATTR, "", null),
                projection(SELECT_ATTR, "", null),
                projection(BINARY_ATTR, "", null),
                projection(TIME_ATTR, "", null),
                projection(I18N_ATTR, "", null)));

        assertEquals(5, rows.size());
        assertEquals("摄像机开启", rows.get(0).getValueText());
        assertEquals("运行", rows.get(1).getValueText());
        assertEquals("开启", rows.get(2).getValueText());
        assertEquals("00:00:00", rows.get(3).getValueText());
        assertEquals("正常", rows.get(4).getValueText());
        for (SdkCustomSnapshotAttr row : rows) {
            assertNull(row.getValue());
            assertNull(row.getUnit());
            assertNull(row.getDisplayPrecision());
            assertEquals("LIVE", row.getSource());
            assertEquals(T0, row.getUpdateTime());
            assertEquals(AttributeStatus.NORMAL.name(), row.getStatus().getKey());
        }
    }

    @Test
    void defExistingAttrSlotWithoutValueReturnsDefRow() {
        registerDevice();
        List<SdkCustomSnapshotAttr> rows = service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, PressureUnit.MPA.getFullUnitString(), 3)));

        assertEquals(1, rows.size());
        SdkCustomSnapshotAttr row = rows.get(0);
        assertEquals(PRESSURE_ATTR, row.getAttrId());
        assertEquals("DEF", row.getSource());
        assertNull(row.getValue());
        assertNull(row.getValueText());
        assertNull(row.getStatus());
        assertNull(row.getUpdateTime());
        assertEquals(PressureUnit.MPA.getFullUnitString(), row.getUnit().getFullName());
        assertEquals(3, row.getDisplayPrecision());
    }

    @Test
    void defRowsPreserveOnlyValidatedNumericProjectionMetadata() {
        registerDevice(nullPressureState(), nullTextState(TEXT_ATTR));

        List<SdkCustomSnapshotAttr> rows = service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, PressureUnit.MPA.getFullUnitString(), 2),
                projection(TEXT_ATTR, "", null)));

        SdkCustomSnapshotAttr numeric = rows.get(0);
        assertEquals("DEF", numeric.getSource());
        assertNull(numeric.getValue());
        assertNull(numeric.getValueText());
        assertNull(numeric.getStatus());
        assertNull(numeric.getUpdateTime());
        assertEquals(PressureUnit.MPA.getFullUnitString(), numeric.getUnit().getFullName());
        assertEquals(2, numeric.getDisplayPrecision());

        SdkCustomSnapshotAttr text = rows.get(1);
        assertEquals("DEF", text.getSource());
        assertNull(text.getValue());
        assertNull(text.getValueText());
        assertNull(text.getUnit());
        assertNull(text.getDisplayPrecision());
        assertNull(text.getStatus());
        assertNull(text.getUpdateTime());
    }

    @Test
    void unregisteredDeviceReturnsEmptyListWithoutChangingExistingSnapshotLifecycle() {
        assertTrue(service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, PressureUnit.MPA.getFullUnitString(), 3))).isEmpty());
    }

    @Test
    void invalidRequestShapesFailFast() {
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(null));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(
                SdkCustomSnapshotRequest.builder().uid("station.invalid").projections(requests()).build()));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(
                SdkCustomSnapshotRequest.builder().uid(UID).projections(Collections.emptyList()).build()));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request((SdkCustomSnapshotProjection) null)));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection("", "", null))));
        registerDevice(pressureState("123.457", 123.45679D, PressureUnit.KPA));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection(TEST_UNKNOWN_ATTR, "", 3))));
    }

    @Test
    void duplicateAttrIdsFailFast() {
        registerDevice(pressureState("123.457", 123.45679D, PressureUnit.KPA));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, "", 1),
                projection(PRESSURE_ATTR, "", 2))));
    }

    @Test
    void invalidNumericUnitAndPrecisionFailFast() {
        registerDevice(pressureState("123.457", 123.45679D, PressureUnit.KPA));
        String[] badUnits = {null, "MPa"};
        for (String unit : badUnits) {
            assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                    projection(PRESSURE_ATTR, unit, 3))));
        }
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, VoltageUnit.VOLT.getFullUnitString(), 3))));
        for (Integer precision : new Integer[]{null, -1, 7}) {
            assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                    projection(PRESSURE_ATTR, "", precision))));
        }
    }

    @Test
    void invalidDisplayProjectionAndMissingDisplayValueFailFast() {
        registerDevice(textState(TEXT_ATTR, "camera_on", null));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection(TEXT_ATTR, null, null))));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection(TEXT_ATTR, TemperatureUnit.CELSIUS.getFullUnitString(), null))));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection(TEXT_ATTR, "", 2))));
        assertThrows(IllegalStateException.class, () -> service.queryCustomSnapshot(request(
                projection(TEXT_ATTR, "", null))));
    }

    @Test
    void invalidLiveNumericValuesAreInternalFailures() {
        registerDevice(pressureState("NaN", Double.NaN, PressureUnit.KPA));
        assertThrows(IllegalStateException.class, () -> service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, "", 3))));
        registerDevice(pressureState("Inf", Double.POSITIVE_INFINITY, PressureUnit.KPA));
        assertThrows(IllegalStateException.class, () -> service.queryCustomSnapshot(request(
                projection(PRESSURE_ATTR, "", 3))));
    }

    @Test
    void nullConversionRatioIsInternalFailure() throws Exception {
        registerDevice(pressureState("123.457", 123.45679D, PressureUnit.KPA));
        java.lang.reflect.Field ratio = PressureUnit.class.getDeclaredField("ratio");
        ratio.setAccessible(true);
        Object old = ratio.get(PressureUnit.KPA);
        ratio.set(PressureUnit.KPA, null);
        try {
            assertThrows(IllegalStateException.class, () -> service.queryCustomSnapshot(request(
                    projection(PRESSURE_ATTR, PressureUnit.MPA.getFullUnitString(), 3))));
        } finally {
            ratio.set(PressureUnit.KPA, old);
        }
    }

    @Test
    void unsupportedComplexAttributeFailsFast() {
        registerDevice(pressureState("123.457", 123.45679D, PressureUnit.KPA), commandState(COMMAND_ATTR));
        assertThrows(IllegalArgumentException.class, () -> service.queryCustomSnapshot(request(
                projection(COMMAND_ATTR, "", null))));
    }

    private SdkCustomSnapshotRequest request(SdkCustomSnapshotProjection... projections) {
        List<SdkCustomSnapshotProjection> list = new ArrayList<>(projections.length);
        list.addAll(Arrays.asList(projections));
        return SdkCustomSnapshotRequest.builder().uid(UID).projections(list).build();
    }

    private List<SdkCustomSnapshotProjection> requests() {
        return Collections.singletonList(projection(PRESSURE_ATTR, "", 3));
    }

    private static SdkCustomSnapshotProjection projection(String attrId, String unit, Integer precision) {
        return SdkCustomSnapshotProjection.builder()
                .attrId(attrId).targetUnitFullName(unit).displayPrecision(precision).build();
    }

    private void registerDevice(AttributeBase<?>... attrs) {
        ConfigEntry entry = new ConfigEntry();
        entry.setEntryId(UID);
        entry.setUniqueId(UID);
        Map<String, Object> data = new HashMap<>();
        data.put("name", "custom snapshot test");
        entry.setData(data);
        LogicDevice device = new LogicDevice(entry) {
            @Override public List<LogicAttributeDefine> getAttrDefs() {
                List<LogicAttributeDefine> defs = new ArrayList<>();
                defs.add(AsmLogicAttributeDefs.numericDef(PRESSURE_ATTR,
                        AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3));
                defs.add(AsmLogicAttributeDefs.textDef(TEXT_ATTR, AttributeClass.TEXT));
                defs.add(AsmLogicAttributeDefs.selectDef(SELECT_ATTR,
                        AttributeClass.ALARM_STATUS, false,
                        StatusOptions.AlarmStatusOptions.OPTIONS));
                defs.add(AsmLogicAttributeDefs.selectDef(BINARY_ATTR,
                        AttributeClass.STATUS, true,
                        AccessControl.LockStatusOptions.OPTIONS));
                defs.add(AsmLogicAttributeDefs.timeDef(TIME_ATTR, AttributeClass.TIME));
                defs.add(AsmLogicAttributeDefs.numericDef(TEST_DIMENSIONLESS_ATTR,
                        AttributeClass.PRESSURE, null, null, 3));
                defs.add(AsmLogicAttributeDefs.textDef(I18N_ATTR, AttributeClass.TEXT));
                defs.add(AsmLogicAttributeDefs.numericDef(TEST_TIE_ATTR,
                        AttributeClass.PRESSURE, null, null, 3));
                defs.add(AsmLogicAttributeDefs.commandDef(COMMAND_ATTR, AttributeClass.COMMAND,
                        false, StandardGas.ACTIVATE_COMMANDS));
                // COMMAND_ATTR 上方已覆盖复杂/命令类：caller-defined snapshot 显式拒绝，不落 displayValue 投影。
                return defs;
            }
            @Override protected String getMappingType() { return "TEST-CUSTOM-SNAPSHOT"; }
            @Override public void start() {}
            @Override public void stop() {}
            @Override public void release() {}
        };
        device.load(EcatCore.getInstance());
        device.init();
        for (AttributeBase<?> attr : attrs) {
            if (attr != null) {
                device.setAttribute(attr);
            }
        }
        deviceRegistry.register(device.getId(), device);
        busRegistry.publish(BusEvent.of(BusTopic.DEVICE_LIFECYCLE.getTopicName(),
                new DeviceLifecycleEvent(device.getId(), "com.ecat:integration-env-air-station-manager",
                        device.getId(), device.getId(), DeviceLifecycleEvent.Action.CREATE),
                EventContext.root(EventContext.Source.SYSTEM, null)));
    }

    private static AttributeBase<?> pressureState(String displayValue, Double value, PressureUnit unit) {
        return pressureState(PRESSURE_ATTR, displayValue, value, unit);
    }

    private static AttributeBase<?> pressureState(String attrId, String displayValue, Double value, PressureUnit unit) {
        AttributeBase<?> attr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(attr.getAttributeID()).thenReturn(attrId);
        lenient().doReturn(AttrState.builder()
                .deviceId(UID).attrId(attrId).valueType(Object.class)
                .status(AttributeStatus.ZERO_CHECK).context(EventContext.root(EventContext.Source.DEVICE_POLL, "test"))
                .value(value).nativeUnit(unit).displayValue(displayValue).lastUpdated(T0)
                .build()).when(attr).getState();
        return attr;
    }

    private static AttributeBase<?> dimensionlessState(String displayValue, Double value) {
        return pressureState(TEST_DIMENSIONLESS_ATTR, displayValue, value, null);
    }

    private static AttributeBase<?> dimensionlessState(String attrId, String displayValue, Double value) {
        return pressureState(attrId, displayValue, value, null);
    }

    private static AttributeBase<?> commandState(String attrId) {
        AttributeBase<?> attr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(attr.getAttributeID()).thenReturn(attrId);
        lenient().doReturn(null).when(attr).getState();
        return attr;
    }

    private static AttributeBase<?> nullPressureState() {
        AttributeBase<?> attr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(attr.getAttributeID()).thenReturn(PRESSURE_ATTR);
        lenient().doReturn(AttrState.builder()
                .deviceId(UID).attrId(PRESSURE_ATTR).valueType(Object.class)
                .status(AttributeStatus.NORMAL).context(EventContext.root(EventContext.Source.DEVICE_POLL, "test"))
                .value(null).lastUpdated(T0)
                .build()).when(attr).getState();
        return attr;
    }

    private static AttributeBase<?> nullTextState(String attrId) {
        AttributeBase<?> attr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(attr.getAttributeID()).thenReturn(attrId);
        lenient().doReturn(AttrState.builder()
                .deviceId(UID).attrId(attrId).valueType(Object.class)
                .status(AttributeStatus.NORMAL).context(EventContext.root(EventContext.Source.DEVICE_POLL, "test"))
                .value(null).lastUpdated(T0)
                .build()).when(attr).getState();
        return attr;
    }

    private static AttributeBase<?> textState(String attrId, Object value, String displayValue) {
        AttributeBase<?> attr = org.mockito.Mockito.mock(AttributeBase.class);
        lenient().when(attr.getAttributeID()).thenReturn(attrId);
        lenient().doReturn(AttrState.builder()
                .deviceId(UID).attrId(attrId).valueType(Object.class)
                .status(AttributeStatus.NORMAL).context(EventContext.root(EventContext.Source.DEVICE_POLL, "test"))
                .value(value).displayValue(displayValue).lastUpdated(T0)
                .build()).when(attr).getState();
        return attr;
    }

    private static AttributeBase<?> selectState(String attrId, Object value, String displayValue) {
        return textState(attrId, value, displayValue);
    }

    private static AttributeBase<?> binaryState(String attrId, Object value, String displayValue) {
        return textState(attrId, value, displayValue);
    }

    private static AttributeBase<?> timeState(String attrId, Object value, String displayValue) {
        return textState(attrId, value, displayValue);
    }
}
