package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryQuery;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryResult;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmHistoryQueryService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * ASM 历史数据查询 controller（{@code GET /asm-monitor/history}，机制同 ADM AdmHistoryQueryController）。
 *
 * <p><b>壁钟→绝对时刻</b>：start/end 是 ISO-8601 LocalDateTime 串（无 Z，前端 datetime-local 提交），
 * controller 按 core JVM 壁钟时区（生产 Asia/Shanghai）经 {@code atZone(JVM_ZONE).toInstant()} 落 UTC
 * 绝对时刻——换算固定在后端 JVM 一侧，不依赖浏览器时区。</p>
 *
 * <p><b>非法入参</b>（粒度非法/时间超限）由 service 抛 {@link IllegalArgumentException} 经 ruoyi 全局
 * 异常处理转 body code=500（HTTP 恒 200，见 memory ruoyi-http-200-body-code-convention）；时间串非法
 * 由 {@link LocalDateTime#parse} 透传 {@code DateTimeParseException}，不在此 catch（保持错误透传供前端 toast）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor")
@RequiredArgsConstructor
public class AsmHistoryQueryController extends BaseController {

    private final AsmHistoryQueryService historyQueryService;

    /** Core 运行时壁钟时区（生产 Asia/Shanghai）；壁钟→绝对时刻固定在 core JVM 一侧。 */
    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    /**
     * 历史数据查询。
     *
     * @param granularity 粒度（MINUTE/FIVE_MIN/HOUR，必填）
     * @param start       起始壁钟时刻（ISO-8601 LocalDateTime 串，无 Z，含）
     * @param end         结束壁钟时刻（同 start 格式，含）
     * @param params      选中参数（逗号分隔 uid:attrId，可空）
     * @param mode        区间模式查看视角（FRONT/BACK，缺省 BACK）
     * @param unit        单位模式（standard/custom，缺省 custom）
     * @param pageNum     页码（缺省 1）
     * @param pageSize    每页桶数（缺省 50）
     * @return AjaxResult.data = {@link AsmHistoryResult}
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:history:query')")
    @GetMapping("/history")
    public AjaxResult history(
            @RequestParam String granularity,
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(value = "params", required = false) String params,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "unit", required = false) String unit,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {

        AsmHistoryQuery query = AsmHistoryQuery.builder()
                .granularity(granularity)
                .start(LocalDateTime.parse(start).atZone(JVM_ZONE).toInstant())
                .end(LocalDateTime.parse(end).atZone(JVM_ZONE).toInstant())
                .params(parseParams(params))
                .mode(mode)
                .unit(unit)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
        AsmHistoryResult result = historyQueryService.query(query);
        return AjaxResult.success(result);
    }

    /**
     * 解析逗号分隔的 {@code uid:attrId} 串。空串/缺省→空列表；单条缺冒号/冒号在首尾跳过
     * （不臆造 attrId，容错单条脏数据不拒整批）。
     */
    private static List<AsmHistoryParamKey> parseParams(String params) {
        if (params == null || params.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<AsmHistoryParamKey> list = new ArrayList<>();
        for (String token : params.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                continue;
            }
            list.add(AsmHistoryParamKey.of(
                    trimmed.substring(0, colon), trimmed.substring(colon + 1)));
        }
        return list;
    }
}
