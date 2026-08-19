package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmControlRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ASM 控制审计记录查询（{@code GET /asm-monitor/control-record/list}）——分页/时间窗/uid/origin/result 过滤
 * （机制同 AsmAlarmRecordController）。PENDING 行异步终态由前端按本端点回查刷新。
 *
 * <p>origin/result 过滤串严格 {@code valueOf}（未知值抛 IllegalArgumentException 经全局异常处理转
 * body code=500，不静默忽略）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/control-record")
@RequiredArgsConstructor
public class AsmControlRecordController extends BaseController {

    /** Core 运行时壁钟时区（生产 Asia/Shanghai）。 */
    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    private final AsmControlRecordMapper recordMapper;

    /**
     * 控制记录分页查询（窗口按 created_at 落窗）。
     *
     * @param start   起始壁钟时刻（ISO LocalDateTime 串，必填）
     * @param end     结束壁钟时刻（同上，必填）
     * @param uid     设备过滤（可选）
     * @param origin  来源过滤（REMOTE/LOCAL，可选）
     * @param result  结果过滤（PENDING/SUCCESS/FAILED/TIMEOUT，可选）
     * @param pageNum 页码（缺省 1）
     * @param pageSize 每页行数（缺省 50，上限 1000）
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:controlRecord:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam String start,
                           @RequestParam String end,
                           @RequestParam(value = "uid", required = false) String uid,
                           @RequestParam(value = "origin", required = false) String origin,
                           @RequestParam(value = "result", required = false) String result,
                           @RequestParam(value = "pageNum", required = false) Integer pageNum,
                           @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start/end 必填");
        }
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 50 : Math.min(pageSize, 1000);
        LocalDateTime from = LocalDateTime.parse(start);
        LocalDateTime to = LocalDateTime.parse(end);
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("start 必须早于 end");
        }
        java.time.Instant startInstant = from.atZone(JVM_ZONE).toInstant();
        java.time.Instant endInstant = to.atZone(JVM_ZONE).toInstant();
        // 严格 valueOf：未知枚举名抛 IllegalArgumentException（不臆造 ALL/忽略）
        AsmControlOrigin originFilter = origin == null || origin.trim().isEmpty()
                ? null : AsmControlOrigin.valueOf(origin.trim());
        AsmControlResult resultFilter = result == null || result.trim().isEmpty()
                ? null : AsmControlResult.valueOf(result.trim());
        long total = recordMapper.countList(uid, originFilter, resultFilter, startInstant, endInstant);
        List<AsmControlRecord> rows = total == 0 ? Collections.<AsmControlRecord>emptyList()
                : recordMapper.selectList(uid, originFilter, resultFilter, startInstant, endInstant, page * size);
        // 简易分页：取前 page*size 后按页切（记录量小；超深翻页由 1000 上限约束）
        int fromIdx = (page - 1) * size;
        List<AsmControlRecord> pageRows = fromIdx >= rows.size() ? Collections.<AsmControlRecord>emptyList()
                : rows.subList(fromIdx, Math.min(fromIdx + size, rows.size()));
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("rows", pageRows);
        return AjaxResult.success(data);
    }
}
