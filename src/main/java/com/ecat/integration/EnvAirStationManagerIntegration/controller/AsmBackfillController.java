package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmStatAggregationEngine;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ASM 手动窗口回补计算端点（e2e/测试提速用，无前端页面）。
 *
 * <p>应用场景：统计物化验证需要「种好数据 → 立即触发指定窗口重算 → 断言」——调度器 hour task
 * 每整点 +180s 才跑一次，e2e 只能干等墙钟整点。本端点把
 * {@link AsmStatAggregationEngine#materializeGranularity}（调度器同入口，引擎注释明言
 * 「调度器 / 手动重算共用」）暴露成 HTTP 手动触发，消除验证对 tick 时钟的依赖。</p>
 *
 * <p>与 ADM {@code /adm-monitor/backfill/recompute} 的差异：ADM 走微批 job 系统（暂停/续算/进度轮询，
 * 面向用户大窗回补）；ASM 本端点是同步单趟最小实现——测试/中小窗（小时~天级）一趟秒级完成，无需
 * job 基建。超大窗口会长时间占用请求线程，需要时再演进微批 job，不提前建。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/backfill")
@RequiredArgsConstructor
public class AsmBackfillController extends BaseController {

    private final AsmStatAggregationEngine engine;

    /**
     * 手动窗口回补计算（同步单趟，POST /asm-monitor/backfill/recompute）。
     *
     * <p>多粒度按级联序自底上执行（minute←raw 先行，5min/hour←minute 后算，mean-of-means 级联
     * 要求上级读到下级新桶）；指定子集（如仅 HOUR）时跳过的下级保持原桶，级联完整性由调用方自担
     * （同 ADM recompute 语义）。窗口两端由引擎按粒度网格对齐（FRONT floor），fetch 右沿以 now
     * 闭合钳制——进行中桶永不写入，与调度路径同一套闭合语义。</p>
     *
     * <p>权限串 {@code asm:backfill:add}：无菜单种子，非超管默认拒绝（默认拒绝是正确姿态，
     * 需要普通角色使用时再配菜单种子）。</p>
     *
     * @param windowStart 回补窗口下界（ISO 8601 UTC instant，如 {@code 2026-09-23T04:00:00Z}）
     * @param windowEnd   回补窗口上界（ISO 8601 UTC instant）
     * @param granularities 可选粒度子集，逗号分隔枚举名（{@code MINUTE/FIVE_MIN/HOUR}，大小写敏感）；
     *                      缺省 = 全三级自底上级联
     * @return {@code {code,msg,perGranularity:{minute:n,5min:n,hour:n},totalRows:n}}；
     *         windowStart 晚于 windowEnd / 未知或重复粒度 → {@code code=400}
     */
    @PreAuthorize("@ss.hasPermi('asm:backfill:add')")
    @PostMapping("/recompute")
    public AjaxResult recompute(@RequestParam Instant windowStart, @RequestParam Instant windowEnd,
                                @RequestParam(required = false) String granularities) {
        try {
            if (windowStart.isAfter(windowEnd)) {
                throw new IllegalArgumentException(
                        "windowStart 须 ≤ windowEnd（start=" + windowStart + " end=" + windowEnd + "）");
            }
            List<AsmStatGranularity> grans = parseGranularities(granularities);
            // 全粒度共用同一 now：与调度路径同 tick 时刻传三级一致，审计基准统一、fetch 右沿闭合口径一致
            Instant now = Instant.now();
            Map<String, Integer> perGranularity = new LinkedHashMap<>();
            int totalRows = 0;
            for (AsmStatGranularity gran : grans) {
                int rows = engine.materializeGranularity(gran, windowStart, windowEnd, "MANUAL", now);
                perGranularity.put(gran.dbCode(), rows);
                totalRows += rows;
            }
            AjaxResult r = AjaxResult.success("回补完成（同步单趟）");
            r.put("perGranularity", perGranularity);
            r.put("totalRows", totalRows);
            return r;
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(400, e.getMessage());
        }
    }

    /**
     * 解析粒度子集参数（包级可见供单测）：null/空白 = 全三级；逗号分隔枚举名大小写敏感；
     * 未知/重复抛 {@link IllegalArgumentException}（调用方错误显式暴露，不静默规范化）。
     * 返回按级联序（EnumSet 自然序 = 声明序 minute→5min→hour）——调用方乱序传入（如 HOUR,MINUTE）
     * 也按级联序执行，保证上级读到下级新桶。
     */
    static List<AsmStatGranularity> parseGranularities(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Arrays.asList(AsmStatGranularity.values());
        }
        Set<AsmStatGranularity> parsed = EnumSet.noneOf(AsmStatGranularity.class);
        for (String token : csv.split(",")) {
            String name = token.trim();
            AsmStatGranularity gran;
            try {
                gran = AsmStatGranularity.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知粒度：" + name + "（可选 MINUTE/FIVE_MIN/HOUR，逗号分隔）");
            }
            if (!parsed.add(gran)) {
                throw new IllegalArgumentException("粒度重复：" + name);
            }
        }
        return new ArrayList<>(parsed);
    }
}
