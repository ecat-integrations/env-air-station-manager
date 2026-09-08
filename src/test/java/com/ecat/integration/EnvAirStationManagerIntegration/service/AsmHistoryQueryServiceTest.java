package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryQuery;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryResult;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 历史查询 service 入参校验与查询路径测试（granularity/mode/unit 解析 + 窗口上限 + 读出口换算接线）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmHistoryQueryServiceTest {

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;

    private AsmHistoryQueryService service;

    private static final Instant START = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-18T01:00:00Z");

    @BeforeEach
    void setUp() {
        service = new AsmHistoryQueryService(historyMapper, unitContract);
    }

    private static AsmHistoryQuery.AsmHistoryQueryBuilder baseQuery() {
        return AsmHistoryQuery.builder()
                .granularity("HOUR")
                .start(START).end(END)
                .params(Collections.singletonList(
                        AsmHistoryParamKey.of("logicdevice_station.d", "voltage")))
                .mode("BACK").unit("custom").pageNum(1).pageSize(50);
    }

    @Test
    void invalidGranularityMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.query(baseQuery().granularity("day").build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.query(baseQuery().granularity(null).build()));
    }

    @Test
    void startNotBeforeEndMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.query(baseQuery().end(START).build()));
    }

    @Test
    void minuteWindowOver31DaysMustThrow() {
        assertThrows(IllegalArgumentException.class, () -> service.query(
                baseQuery().granularity("MINUTE")
                        .start(START).end(START.plusSeconds(32L * 24 * 3600)).build()));
    }

    @Test
    void hourWindowOver400DaysMustThrow() {
        assertThrows(IllegalArgumentException.class, () -> service.query(
                baseQuery().granularity("HOUR")
                        .start(START).end(START.plusSeconds(401L * 24 * 3600)).build()));
    }

    @Test
    void emptyParamsMustReturnEmptyResult() {
        assertEquals(0, service.query(baseQuery().params(Collections.emptyList()).build())
                .getRows().size());
    }

    @Test
    void invalidModeMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.query(baseQuery().mode("MIDDLE").build()));
    }

    @Test
    void queryMustConvertValueViaHistoryPurposeAndKeepAscRows() {
        AsmHistoryBucket bucket = AsmHistoryBucket.builder()
                .dataTime(START).logicDeviceUniqueId("logicdevice_station.d").attrId("voltage")
                .avgValue(220.0).validCount(5L).totalCount(6L)
                .build();
        when(historyMapper.selectStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(bucket));
        // 换算源单位 = STORAGE 行 unit（桶单位真相源），先 stub resolveUnit(STORAGE)
        when(unitContract.resolveUnit(
                eq(com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose.STORAGE),
                eq("logicdevice_station.d"), eq("voltage")))
                .thenReturn(VoltageUnit.VOLT.getFullUnitString());
        // HISTORY 出口换算：V→mV ×1000（mock 走真实 AsmUnitContract.resolveDisplay 的可换算对语义）
        when(unitContract.resolveDisplay(
                eq(com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose.HISTORY),
                eq("logicdevice_station.d"), eq("voltage"), eq(220.0),
                eq(VoltageUnit.VOLT.getFullUnitString())))
                .thenReturn(AsmDisplayValue.of(220000.0, VoltageUnit.MILLIVOLT.getFullUnitString(), true));

        AsmHistoryResult result = service.query(baseQuery().build());

        assertEquals(1, result.getRows().size());
        assertEquals(Double.valueOf(220000.0), result.getRows().get(0).getValue());
        assertEquals(VoltageUnit.MILLIVOLT.getFullUnitString(), result.getRows().get(0).getUnit());
        // display_unit 取换算出口实际单位（V→mV 后=mV，非 storageUnit 的 V）
        assertEquals("mV", result.getRows().get(0).getDisplayUnit());
    }

    @Test
    void standardModeRowDisplayUnitFromStorageUnitSameSource() {
        AsmHistoryBucket bucket = AsmHistoryBucket.builder()
                .dataTime(START).logicDeviceUniqueId("logicdevice_station.d").attrId("voltage")
                .avgValue(220.0).validCount(5L).totalCount(6L)
                .build();
        when(historyMapper.selectStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(bucket));
        when(unitContract.resolveUnit(
                eq(com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose.STORAGE),
                eq("logicdevice_station.d"), eq("voltage")))
                .thenReturn(VoltageUnit.VOLT.getFullUnitString());

        AsmHistoryResult result = service.query(baseQuery().unit("standard").build());

        // standard 恒原生直通：值/单位不换算，display_unit=storageUnit 同源转换（V）
        assertEquals(1, result.getRows().size());
        assertEquals(Double.valueOf(220.0), result.getRows().get(0).getValue());
        assertEquals(VoltageUnit.VOLT.getFullUnitString(), result.getRows().get(0).getUnit());
        assertEquals("V", result.getRows().get(0).getDisplayUnit());
        verify(unitContract, never()).resolveDisplay(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void defaultModeAndUnitMustResolve() {
        // mode 缺省 BACK（国标后闭）/ unit 缺省 custom（应用 HISTORY 偏好）
        when(historyMapper.selectStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        AsmHistoryQuery q = baseQuery().mode(null).unit(null).build();
        service.query(q);
        // 不抛即默认值解析成功（mode=null → BACK=2 code 传入 mapper）
    }

    @Test
    void historyRow_serializesDisplayUnitSnakeCase_plusOldFieldsUnchanged() throws Exception {
        // 契约锁定：rows 行 display_unit 为 snake_case 键，旧字段（unit/value/…）路径不变。
        // dataTime 置 null——Instant 序列化依赖 Spring Boot 注册的 JavaTimeModule（生产出口已具备），
        // 裸 ObjectMapper 不引入该可选模块，本测试只锁键名契约不锁时刻格式。
        AsmHistoryResult.Row row = AsmHistoryResult.Row.builder()
                .dataTime(null).logicDeviceUniqueId("logicdevice_station.d").attrId("voltage")
                .value(220.0).unit("VoltageUnit.VOLT").displayUnit("V")
                .validCount(5L).totalCount(6L).build();
        String json = new ObjectMapper().writeValueAsString(row);
        assertTrue(json.contains("\"unit\":\"VoltageUnit.VOLT\"") && json.contains("\"value\":220.0"),
                "旧字段 JSON 键不得变：" + json);
        assertTrue(json.contains("\"display_unit\":\"V\""), "display_unit 须为 snake_case 键：" + json);
    }
}
