package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmControlRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 控制记录列表端点：时间窗/uid/origin/result 过滤解析 + 分页缺省（机制同 AsmAlarmRecordController）；
 * origin/result 字符串严格 valueOf（未知值抛 IllegalArgumentException 不静默忽略）。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmControlRecordControllerTest {

    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    @Mock
    private AsmControlRecordMapper recordMapper;

    private AsmControlRecordController controller;

    @BeforeEach
    void setUp() {
        controller = new AsmControlRecordController(recordMapper);
    }

    @Test
    void list_parsesFiltersAndAppliesPagingDefaults() {
        Instant start = LocalDateTime.parse("2026-08-15T00:00:00").atZone(JVM_ZONE).toInstant();
        Instant end = LocalDateTime.parse("2026-08-15T23:00:00").atZone(JVM_ZONE).toInstant();
        AsmControlRecord row = AsmControlRecord.builder()
                .id(1L).origin(AsmControlOrigin.REMOTE).result(AsmControlResult.PENDING).build();
        when(recordMapper.countList("uid-1", AsmControlOrigin.REMOTE, AsmControlResult.PENDING, start, end)).thenReturn(1L);
        when(recordMapper.selectList("uid-1", AsmControlOrigin.REMOTE, AsmControlResult.PENDING, start, end, 50))
                .thenReturn(Collections.singletonList(row));

        AjaxResult result = controller.list("2026-08-15T00:00:00", "2026-08-15T23:00:00",
                "uid-1", "REMOTE", "PENDING", null, null);

        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1L, data.get("total"));
        assertEquals(1, ((List<?>) data.get("rows")).size());
        InOrder inOrder = inOrder(recordMapper);
        inOrder.verify(recordMapper).countList("uid-1", AsmControlOrigin.REMOTE, AsmControlResult.PENDING, start, end);
        inOrder.verify(recordMapper).selectList("uid-1", AsmControlOrigin.REMOTE, AsmControlResult.PENDING, start, end, 50);
    }

    @Test
    void list_nullFiltersPassedThroughAsNull() {
        Instant start = LocalDateTime.parse("2026-08-15T00:00:00").atZone(JVM_ZONE).toInstant();
        Instant end = LocalDateTime.parse("2026-08-15T01:00:00").atZone(JVM_ZONE).toInstant();
        when(recordMapper.countList(isNull(String.class), isNull(AsmControlOrigin.class),
                isNull(AsmControlResult.class), eq(start), eq(end))).thenReturn(0L);

        AjaxResult result = controller.list("2026-08-15T00:00:00", "2026-08-15T01:00:00",
                null, null, null, null, null);

        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(0L, data.get("total"));
        assertEquals(0, ((List<?>) data.get("rows")).size());
        org.mockito.Mockito.verify(recordMapper, never())
                .selectList(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void list_invalidOriginThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("2026-08-15T00:00:00", "2026-08-15T01:00:00", null, "NOT_A_ORIGIN", null, null, null));
    }

    @Test
    void list_invalidResultThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("2026-08-15T00:00:00", "2026-08-15T01:00:00", null, null, "MAYBE", null, null));
    }

    @Test
    void list_startNotBeforeEndThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("2026-08-15T02:00:00", "2026-08-15T01:00:00", null, null, null, null, null));
    }
}
