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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
                "logicdevice_station.d:voltage", "BACK", "custom", 1, 50);

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
    void malformedTimeStringsMustPropagateParseError() {
        assertThrows(Exception.class, () -> new AsmHistoryQueryController(service).history(
                "HOUR", "not-a-time", "2026-08-18T16:00:00", null, null, null, null, null));
    }

    @Test
    void nullParamsMustYieldEmptyParamList() {
        when(service.query(any())).thenReturn(null);
        new AsmHistoryQueryController(service).history(
                "HOUR", "2026-08-18T15:00:00", "2026-08-18T16:00:00",
                null, null, null, null, null);
        ArgumentCaptor<AsmHistoryQuery> captor = ArgumentCaptor.forClass(AsmHistoryQuery.class);
        verify(service).query(captor.capture());
        assertEquals(Collections.emptyList(), captor.getValue().getParams());
    }
}
