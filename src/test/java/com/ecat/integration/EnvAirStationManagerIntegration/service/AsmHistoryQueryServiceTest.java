package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryQuery;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryResult;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        AsmHistoryResult result = service.query(baseQuery().params(Collections.emptyList()).build());
        assertEquals(0, result.getRows().size());
        assertEquals(0L, result.getTotal(), "空参数零查询：total 恒 0（不发起 count）");
    }

    @Test
    void queryMustReturnTotalFromCountWithSameWindowAndMode() {
        // 真分页 total：count 与行集同表/同窗/同 mode/同参数集（mapper 同一过滤段），service 只透传
        AsmHistoryBucket bucket = AsmHistoryBucket.builder()
                .dataTime(START).logicDeviceUniqueId("logicdevice_station.d").attrId("voltage")
                .avgValue(220.0).validCount(5L).totalCount(6L)
                .build();
        when(historyMapper.selectStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(bucket));
        when(historyMapper.countStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt()))
                .thenReturn(1440L);
        when(unitContract.resolveUnit(
                eq(com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose.STORAGE),
                eq("logicdevice_station.d"), eq("voltage")))
                .thenReturn(VoltageUnit.VOLT.getFullUnitString());

        // unit=standard：只测 total 透传与 count 同参，不引入 HISTORY 换算 stub 噪声（换算另有专测）
        AsmHistoryResult result = service.query(baseQuery().pageSize(200).unit("standard").build());

        assertEquals(1440L, result.getTotal(), "total=count 出参透传（窗口桶行总数，非本页行数）");
        assertEquals(1, result.getRows().size());
        // count 与行集同参：同表 / 同窗 / 同参数集 / 同 mode（口径一致性由 mapper 同一过滤段保证）
        verify(historyMapper).countStatRows(
                eq(AsmStatGranularity.HOUR.targetTable()), eq(START), eq(END),
                eq(Collections.singletonList(AsmHistoryParamKey.of("logicdevice_station.d", "voltage"))),
                eq(AsmIntervalMode.BACK.code()));
    }

    @Test
    void invalidModeMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.query(baseQuery().mode("MIDDLE").build()));
    }

    @Test
    void descOrderMustRouteToDescStatementAndAscMustKeepLegacyStatement() {
        // order 分流两条独立语句：DESC=历史页网格分页「最新在前」；ASC/缺省=SDK 升序契约语句零扰动。
        // 两条语句过滤/分页参数完全同构（同表/同窗/同参/同 mode/同分页），仅方向不同。
        when(historyMapper.selectStatRowsDesc(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(historyMapper.selectStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.query(baseQuery().order("DESC").build());
        verify(historyMapper).selectStatRowsDesc(
                eq(AsmStatGranularity.HOUR.targetTable()), eq(START), eq(END), anyList(),
                eq(AsmIntervalMode.BACK.code()), eq(50), eq(0));
        verify(historyMapper, never()).selectStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt());

        service.query(baseQuery().order(null).build());
        service.query(baseQuery().order("ASC").build());
        // 缺省/显式 ASC 各走一次升序语句（SDK 同一入口），降序语句不再追加调用
        verify(historyMapper, times(2)).selectStatRows(
                anyString(), any(Instant.class), any(Instant.class), anyList(), anyInt(), anyInt(), anyInt());
        verify(historyMapper, times(1)).selectStatRowsDesc(
                anyString(), any(Instant.class), any(Instant.class), anyList(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void invalidOrderMustThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.query(baseQuery().order("desc").build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.query(baseQuery().order("RANDOM").build()));
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

    @Test
    void historyResult_serializesTotalAlongsidePagingFields() throws Exception {
        // 契约锁定：total 与 pageNum/pageSize/rows 同级（前端真分页读 total 算页数，缺级=退化探测）
        AsmHistoryResult result = AsmHistoryResult.builder()
                .granularity("HOUR").mode("BACK").unit("custom")
                .pageNum(1).pageSize(200).total(1440L)
                .rows(Collections.<AsmHistoryResult.Row>emptyList())
                .build();
        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("\"total\":1440") && json.contains("\"pageNum\":1") && json.contains("\"pageSize\":200"),
                "total 须与分页字段同级同形：" + json);
    }

    @Test
    void nonNumericBucketRowPassesValueTextThroughWithNullValue() {
        // 非数值桶（avgValue null + valueText 非空）：value 恒 null（resolveDisplay 对 null 直通不抛）、
        // valueText 原样透传、unit=storageUnit（seed 空串占位=显无单位）
        AsmHistoryBucket bucket = AsmHistoryBucket.builder()
                .dataTime(START).logicDeviceUniqueId("logicdevice_station.security_alarm").attrId("water_leak")
                .valueText("alarm").validCount(3L).totalCount(4L)
                .build();
        when(historyMapper.selectStatRows(anyString(), any(Instant.class), any(Instant.class),
                anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(bucket));
        when(unitContract.resolveUnit(
                eq(com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose.STORAGE),
                eq("logicdevice_station.security_alarm"), eq("water_leak")))
                .thenReturn("");
        // unit=custom 走 HISTORY 换算出口；真实现 value=null 直通返（此处 mock 复现同语义）
        when(unitContract.resolveDisplay(
                eq(com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose.HISTORY),
                eq("logicdevice_station.security_alarm"), eq("water_leak"), isNull(), eq("")))
                .thenReturn(AsmDisplayValue.of(null, "", false));

        AsmHistoryResult result = service.query(baseQuery().build());

        assertEquals(1, result.getRows().size());
        AsmHistoryResult.Row row = result.getRows().get(0);
        assertEquals("alarm", row.getValueText(), "文本统计值原样透传（不换算）");
        assertEquals(null, row.getValue(), "非数值桶无均值，value=null");
        assertEquals("", row.getUnit(), "unit=seed 空串占位（显无单位）");
        assertEquals(null, row.getDisplayUnit(), "空串单位无显示符号");
    }

    @Test
    void numericBucketRowKeepsNullValueText() {
        // 数值桶回归：valueText 恒 null（互斥不变式），value/unit 换算路径不变
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

        assertEquals(null, result.getRows().get(0).getValueText());
        assertEquals(Double.valueOf(220.0), result.getRows().get(0).getValue());
    }
}
