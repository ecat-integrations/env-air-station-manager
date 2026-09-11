package com.ecat.integration.EnvAirStationManagerIntegration.api;

import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AirStationSdkImpl;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSnapshotService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SDK 控制出口契约（{@code control(SdkControlRequest)}）：origin 由调用方声明并回显、caller 透传、
 * unit 三态（null 拒绝/空串=不指定/full string 解析）与请求形态校验矩阵（同步 fail-fast 不落审计）。
 * 设备侧校验（前缀/存在/可写）与终态模型归统一控制服务，由 {@code AsmControlServiceTest} 锁。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AirStationSdkControlTest {

    private static final String UID = "logicdevice_station.th";
    private static final String CALLER = "com.ecat:integration-x";

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private AsmSnapshotService snapshotService;
    @Mock
    private AsmAlarmRecordMapper alarmRecordMapper;
    @Mock
    private AsmControlService controlService;
    @Mock
    private AsmDeviceLabelService labelService;

    private AirStationSdk sdk;

    @BeforeEach
    void setUp() {
        sdk = new AirStationSdkImpl(historyMapper, unitContract, snapshotService,
                alarmRecordMapper, labelService, controlService);
    }

    private static SdkControlRequest request(String unit, AsmControlOrigin origin, String caller) {
        return SdkControlRequest.builder()
                .uid(UID).attrId("temperature").value("30.0")
                .unit(unit).origin(origin).caller(caller)
                .build();
    }

    // ===== 委派链：origin 由调用方声明、caller 透传、结果回显 =====

    @Test
    void control_localRequestDelegatesWithDeclaredOriginAndCaller() {
        AsmControlRecord rec = AsmControlRecord.builder()
                .id(11L).origin(AsmControlOrigin.LOCAL).caller(CALLER)
                .logicDeviceUniqueId(UID).attrId("temperature")
                .action("WRITE").requestedValue("30.0")
                .result(AsmControlResult.SUCCESS).durationMs(120L).build();
        when(controlService.execute(AsmControlOrigin.LOCAL, CALLER, UID, "temperature",
                "30.0", null)).thenReturn(rec);

        SdkControlResult out = sdk.control(request("", AsmControlOrigin.LOCAL, CALLER));

        verify(controlService).execute(AsmControlOrigin.LOCAL, CALLER, UID, "temperature",
                "30.0", null);
        assertEquals(Long.valueOf(11L), out.getRecordId());
        assertEquals("LOCAL", out.getOrigin(), "origin 回显请求声明值");
        assertEquals("SUCCESS", out.getResult());
        assertEquals(Long.valueOf(120L), out.getDurationMs());
    }

    @Test
    void control_remoteRequestEchoesRemoteOrigin() {
        // 第三方代传场景：origin/最终用户标识都由 SDK 调用方声明（集成不代做决定）
        when(controlService.execute(AsmControlOrigin.REMOTE, "platformA:user123", UID,
                "temperature", "30.0", null))
                .thenReturn(AsmControlRecord.builder().id(12L)
                        .origin(AsmControlOrigin.REMOTE).caller("platformA:user123")
                        .result(AsmControlResult.PENDING).build());

        SdkControlResult out = sdk.control(request("", AsmControlOrigin.REMOTE, "platformA:user123"));

        assertEquals("REMOTE", out.getOrigin());
        assertEquals("PENDING", out.getResult());
    }

    // ===== unit 三态 =====

    @Test
    void control_unitFullString_resolvedAndPassedAsFromUnit() {
        when(controlService.execute(eq(AsmControlOrigin.LOCAL), eq(CALLER), eq(UID),
                eq("temperature"), eq("30.0"), eq(TemperatureUnit.CELSIUS)))
                .thenReturn(AsmControlRecord.builder().id(13L)
                        .origin(AsmControlOrigin.LOCAL).result(AsmControlResult.PENDING).build());

        sdk.control(request("TemperatureUnit.CELSIUS", AsmControlOrigin.LOCAL, CALLER));

        verify(controlService).execute(eq(AsmControlOrigin.LOCAL), eq(CALLER), eq(UID),
                eq("temperature"), eq("30.0"), eq(TemperatureUnit.CELSIUS));
    }

    @Test
    void control_unitResolvedValueIsTheRealUnitInstance() {
        // 单位对象直传断言（不依赖 eq 匹配）：full string 解析产物须为 core 同一枚举常量
        when(controlService.execute(any(), any(), any(), any(), any(), any()))
                .thenReturn(AsmControlRecord.builder().id(14L).build());
        ArgumentCaptor<com.ecat.core.State.UnitInfo> unit =
                ArgumentCaptor.forClass(com.ecat.core.State.UnitInfo.class);

        sdk.control(request("AirVolumeUnit.PPM", AsmControlOrigin.LOCAL, CALLER));

        verify(controlService).execute(any(), any(), any(), any(), any(), unit.capture());
        assertEquals(AirVolumeUnit.PPM, unit.getValue());
    }

    @Test
    void control_blankUnit_meansNoUnitConversion() {
        when(controlService.execute(any(), any(), any(), any(), any(), any()))
                .thenReturn(AsmControlRecord.builder().id(15L).build());

        sdk.control(request("  ", AsmControlOrigin.LOCAL, CALLER));

        verify(controlService).execute(any(), any(), any(), any(), any(), isNull());
    }

    @Test
    void control_nullUnit_rejectedSyncWithoutAuditRow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sdk.control(request(null, AsmControlOrigin.LOCAL, CALLER)));

        assertTrue(e.getMessage().contains("unit"), "消息须点名字段: " + e.getMessage());
        verifyNoInteractions(controlService);
    }

    @Test
    void control_symbolUnit_rejectedWithFullStringGuidance() {
        // °C 是展示层 getName 输出，禁止作传输值——拒绝消息须给出合法格式示例
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sdk.control(request("°C", AsmControlOrigin.LOCAL, CALLER)));

        assertTrue(e.getMessage().contains("TemperatureUnit.CELSIUS"), "消息须给合法格式示例: " + e.getMessage());
        verifyNoInteractions(controlService);
    }

    // ===== 请求形态校验矩阵（同步 fail-fast，不落审计行；扁平 @Test——本模块 surefire 不发现 @Nested）=====

    private void assertRejected(SdkControlRequest bad, String field) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sdk.control(bad), field + " 非法须同步拒绝");
        assertTrue(e.getMessage().contains(field), field + " 消息须点名字段: " + e.getMessage());
        verifyNoInteractions(controlService);
    }

    @Test
    void control_nullRequestRejected() {
        assertThrows(IllegalArgumentException.class, () -> sdk.control(null));
        verifyNoInteractions(controlService);
    }

    @Test
    void control_blankUidRejected() {
        assertRejected(SdkControlRequest.builder().uid(" ").attrId("temperature").value("30.0")
                .unit("").origin(AsmControlOrigin.LOCAL).caller(CALLER).build(), "uid");
    }

    @Test
    void control_blankAttrIdRejected() {
        assertRejected(SdkControlRequest.builder().uid(UID).attrId(null).value("30.0")
                .unit("").origin(AsmControlOrigin.LOCAL).caller(CALLER).build(), "attrId");
    }

    @Test
    void control_blankValueRejected() {
        assertRejected(SdkControlRequest.builder().uid(UID).attrId("temperature").value("")
                .unit("").origin(AsmControlOrigin.LOCAL).caller(CALLER).build(), "value");
    }

    @Test
    void control_nullOriginRejected() {
        assertRejected(request("", null, CALLER), "origin");
    }

    @Test
    void control_blankCallerRejected() {
        assertRejected(request("", AsmControlOrigin.REMOTE, " "), "caller");
    }
}
