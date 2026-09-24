package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmStatAggregationEngine;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 手动回补端点：粒度子集解析契约（缺省全三级/级联序规范化/未知·重复拒绝）+ 引擎透传
 * （triggerSource=MANUAL、三级共用同一 now、级联序执行）+ 非法窗口 400 映射。
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class AsmBackfillControllerTest {

    @Mock
    private AsmStatAggregationEngine engine;

    private AsmBackfillController controller;

    @BeforeEach
    void setUp() {
        controller = new AsmBackfillController(engine);
    }

    @Test
    void parseGranularities_nullDefaultsToAllInCascadeOrder() {
        List<AsmStatGranularity> grans = AsmBackfillController.parseGranularities(null);
        assertEquals(Arrays.asList(AsmStatGranularity.MINUTE, AsmStatGranularity.FIVE_MIN, AsmStatGranularity.HOUR),
                grans, "缺省=全三级，且按级联序（minute→5min→hour）");
    }

    @Test
    void parseGranularities_blankDefaultsToAll() {
        // 前端/脚本可能显式传空串——按缺省处理（区别于段内空 token 的显式拒绝）
        assertEquals(3, AsmBackfillController.parseGranularities("  ").size());
    }

    @Test
    void parseGranularities_outOfOrderInputNormalizedToCascadeOrder() {
        List<AsmStatGranularity> grans = AsmBackfillController.parseGranularities("HOUR,MINUTE");
        assertEquals(Arrays.asList(AsmStatGranularity.MINUTE, AsmStatGranularity.HOUR), grans,
                "乱序传入按级联序执行，保证上级读到下级新桶");
    }

    @Test
    void parseGranularities_unknownNameRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsmBackfillController.parseGranularities("MINUTE,DAY"));
        assertTrue(e.getMessage().contains("未知粒度"), "未知粒度（ASM 无 DAY 级）须显式拒绝: " + e.getMessage());
    }

    @Test
    void parseGranularities_duplicateRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AsmBackfillController.parseGranularities("MINUTE ,MINUTE"));
    }

    @Test
    void recompute_delegatesCascadeWithManualSourceAndSharedNow() {
        Instant ws = Instant.parse("2026-09-23T04:00:00Z");
        Instant we = Instant.parse("2026-09-23T06:00:00Z");
        when(engine.materializeGranularity(eq(AsmStatGranularity.MINUTE), any(), any(), eq("MANUAL"), any()))
                .thenReturn(120);
        when(engine.materializeGranularity(eq(AsmStatGranularity.FIVE_MIN), any(), any(), eq("MANUAL"), any()))
                .thenReturn(24);
        when(engine.materializeGranularity(eq(AsmStatGranularity.HOUR), any(), any(), eq("MANUAL"), any()))
                .thenReturn(2);

        AjaxResult result = controller.recompute(ws, we, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        InOrder order = inOrder(engine);
        order.verify(engine).materializeGranularity(eq(AsmStatGranularity.MINUTE), eq(ws), eq(we), eq("MANUAL"), any());
        order.verify(engine).materializeGranularity(eq(AsmStatGranularity.FIVE_MIN), eq(ws), eq(we), eq("MANUAL"), any());
        order.verify(engine).materializeGranularity(eq(AsmStatGranularity.HOUR), eq(ws), eq(we), eq("MANUAL"), any());
        // 三级共用同一 now（与调度路径同 tick 时刻口径一致）——窗口原样透传（网格对齐是引擎职责）
        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(engine, times(3))
                .materializeGranularity(any(), any(), any(), eq("MANUAL"), nowCaptor.capture());
        assertSame(nowCaptor.getAllValues().get(0), nowCaptor.getAllValues().get(1), "三级须共用同一 now 实例");
        assertSame(nowCaptor.getAllValues().get(1), nowCaptor.getAllValues().get(2), "三级须共用同一 now 实例");
        assertNotNull(nowCaptor.getAllValues().get(0));

        @SuppressWarnings("unchecked")
        Map<String, Integer> perGran = (Map<String, Integer>) result.get("perGranularity");
        assertEquals(Integer.valueOf(120), perGran.get("minute"));
        assertEquals(Integer.valueOf(24), perGran.get("5min"));
        assertEquals(Integer.valueOf(2), perGran.get("hour"));
        assertEquals(Integer.valueOf(146), (Integer) result.get("totalRows"));
    }

    @Test
    void recompute_subsetOnlyCallsSubset() {
        Instant ws = Instant.parse("2026-09-23T04:00:00Z");
        Instant we = Instant.parse("2026-09-23T06:00:00Z");
        when(engine.materializeGranularity(eq(AsmStatGranularity.HOUR), any(), any(), eq("MANUAL"), any()))
                .thenReturn(2);

        AjaxResult result = controller.recompute(ws, we, "HOUR");

        verify(engine, times(1))
                .materializeGranularity(eq(AsmStatGranularity.HOUR), any(), any(), eq("MANUAL"), any());
        verify(engine, never())
                .materializeGranularity(eq(AsmStatGranularity.MINUTE), any(), any(), any(), any());
        verify(engine, never())
                .materializeGranularity(eq(AsmStatGranularity.FIVE_MIN), any(), any(), any(), any());
        assertEquals(Integer.valueOf(2), (Integer) result.get("totalRows"));
    }

    @Test
    void recompute_reversedWindowMapsTo400() {
        AjaxResult result = controller.recompute(
                Instant.parse("2026-09-23T06:00:00Z"), Instant.parse("2026-09-23T04:00:00Z"), null);
        assertEquals(400, result.get(AjaxResult.CODE_TAG), "窗口倒置须 400 明确告知");
        verifyNoInteractions(engine);
    }

    @Test
    void recompute_unknownGranularityMapsTo400() {
        AjaxResult result = controller.recompute(
                Instant.parse("2026-09-23T04:00:00Z"), Instant.parse("2026-09-23T06:00:00Z"), "DAY");
        assertEquals(400, result.get(AjaxResult.CODE_TAG), "未知粒度（ASM 无 DAY）须 400 明确告知");
        verifyNoInteractions(engine);
    }
}
