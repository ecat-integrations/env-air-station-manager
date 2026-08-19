package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ASM 报警记录查询（{@code GET /asm-monitor/alarm-record}）——分页/时间窗/设备过滤。
 *
 * <p>start/end 为 ISO-8601 LocalDateTime 壁钟串（前端 datetime-local），controller 按 core JVM 壁钟时区
 * 落 UTC 绝对时刻（与 AsmHistoryQueryController 同口径）。查询窗按 end_time 落窗（触发/恢复时刻）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/alarm-record")
@RequiredArgsConstructor
public class AsmAlarmRecordController extends BaseController {

    /** Core 运行时壁钟时区（生产 Asia/Shanghai）。 */
    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    private final AsmAlarmRecordMapper recordMapper;

    /**
     * 报警记录分页查询。
     *
     * @param start   起始壁钟时刻（ISO LocalDateTime 串，必填）
     * @param end     结束壁钟时刻（同上，必填）
     * @param uid     设备过滤（可选）
     * @param pageNum 页码（缺省 1）
     * @param pageSize 每页行数（缺省 50，上限 1000）
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:alarmRecord:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam String start,
                           @RequestParam String end,
                           @RequestParam(value = "uid", required = false) String uid,
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
        long total = recordMapper.countList(uid, startInstant, endInstant);
        List<AsmAlarmRecord> rows = total == 0 ? java.util.Collections.emptyList()
                : recordMapper.selectList(uid, startInstant, endInstant, page * size);
        // 简易分页：取前 page*size 后按页切（记录量小；超深翻页由 1000 上限约束）
        int fromIdx = (page - 1) * size;
        List<AsmAlarmRecord> pageRows = fromIdx >= rows.size() ? java.util.Collections.<AsmAlarmRecord>emptyList()
                : rows.subList(fromIdx, Math.min(fromIdx + size, rows.size()));
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("rows", pageRows);
        return AjaxResult.success(data);
    }
}
