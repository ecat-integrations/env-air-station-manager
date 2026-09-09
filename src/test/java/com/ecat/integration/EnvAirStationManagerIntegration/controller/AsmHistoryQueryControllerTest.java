package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryQuery;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmHistoryQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 历史 controller 参数解析测试：壁钟串→JVM_ZONE→UTC 绝对时刻（不依赖浏览器时区）；
 * params 逗号拆分；非法时间串透传 DateTimeParseException（ruoyi 全局处理转 body code=500）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmHistoryQueryControllerTest {

    @Mock
    private AsmHistoryQueryService service;

    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    @Test
    void wallClockStringsMustConvertToUtcInstantsViaJvmZone() {
        when(service.query(any())).thenReturn(null);
        new AsmHistoryQueryController(service).history(
                "HOUR", "2026-08-18T15:00:00", "2026-08-18T16:00:00",
                "logicdevice_station.d:voltage", "BACK", "custom", 1, 50, "DESC");

        ArgumentCaptor<AsmHistoryQuery> captor = ArgumentCaptor.forClass(AsmHistoryQuery.class);
        verify(service).query(captor.capture());
        AsmHistoryQuery q = captor.getValue();
        assertEquals(LocalDateTime.parse("2026-08-18T15:00:00").atZone(JVM_ZONE).toInstant(),
                q.getStart());
        assertEquals(LocalDateTime.parse("2026-08-18T16:00:00").atZone(JVM_ZONE).toInstant(),
                q.getEnd());
        assertEquals(1, q.getParams().size());
        assertEquals("logicdevice_station.d", q.getParams().get(0).getLogicDeviceUniqueId());
        assertEquals("voltage", q.getParams().get(0).getAttrId());
    }

    @Test
    void orderMustPassThroughToQuery() {
        // 降序透传（历史页网格分页「最新在前」）：controller 只透传，合法性归 service
        when(service.query(any())).thenReturn(null);
        new AsmHistoryQueryController(service).history(
                "MINUTE", "2026-08-18T15:00:00", "2026-08-18T16:00:00",
                null, null, null, 1, 2000, "DESC");
        ArgumentCaptor<AsmHistoryQuery> captor = ArgumentCaptor.forClass(AsmHistoryQuery.class);
        verify(service).query(captor.capture());
        assertEquals("DESC", captor.getValue().getOrder());
    }

    @Test
    void pageSizeMustBeClampedToGuardrailButPreservedWithinIt() {
        // 护栏钳位只封异常放大调用（网格分页 pageSize=页 tick 数×参数数）：超限钳到 20000、
        // 限内原样透传、null 不造缺省值（缺省语义归 service）
        when(service.query(any())).thenReturn(null);
        AsmHistoryQueryController controller = new AsmHistoryQueryController(service);

        controller.history("MINUTE", "2026-08-18T15:00:00", "2026-08-18T16:00:00",
                null, null, null, 1, AsmHistoryQueryController.MAX_REST_PAGE_SIZE + 500, "DESC");
        controller.history("MINUTE", "2026-08-18T15:00:00", "2026-08-18T16:00:00",
                null, null, null, 1, 2000, "DESC");
        controller.history("MINUTE", "2026-08-18T15:00:00", "2026-08-18T16:00:00",
                null, null, null, null, null, null);

        ArgumentCaptor<AsmHistoryQuery> captor = ArgumentCaptor.forClass(AsmHistoryQuery.class);
        verify(service, times(3)).query(captor.capture());
        List<AsmHistoryQuery> queries = captor.getAllValues();
        assertEquals(AsmHistoryQueryController.MAX_REST_PAGE_SIZE, queries.get(0).getPageSize(),
                "超护栏 pageSize 须钳位到 MAX_REST_PAGE_SIZE");
        assertEquals(Integer.valueOf(2000), queries.get(1).getPageSize(), "护栏内 pageSize 原样透传");
        assertEquals(null, queries.get(2).getPageSize(), "缺省 pageSize 不在 controller 造值");
    }

    @Test
    void malformedTimeStringsMustPropagateParseError() {
        assertThrows(Exception.class, () -> new AsmHistoryQueryController(service).history(
                "HOUR", "not-a-time", "2026-08-18T16:00:00", null, null, null, null, null, null));
    }

    @Test
    void nullParamsMustYieldEmptyParamList() {
        when(service.query(any())).thenReturn(null);
        new AsmHistoryQueryController(service).history(
                "HOUR", "2026-08-18T15:00:00", "2026-08-18T16:00:00",
                null, null, null, null, null, null);
        ArgumentCaptor<AsmHistoryQuery> captor = ArgumentCaptor.forClass(AsmHistoryQuery.class);
        verify(service).query(captor.capture());
        assertEquals(Collections.emptyList(), captor.getValue().getParams());
    }
}
