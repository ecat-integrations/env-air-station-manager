package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmSnapshotDeviceDto;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSnapshotService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ASM 站房设备当前态快照 controller（{@code GET /asm-monitor/snapshot}）。
 *
 * <p>应用场景：前端站房设备总览页 onMounted 拉一次全量当前态初始化卡片。数据经
 * {@link AsmSnapshotService}：live AttrState 只读经 {@code getState()}（无撕裂读，属性状态契约 §15）
 * + MONITOR 读出口单位换算；live 缺席 attr 用 raw 最新值回放（source=RAW 标记）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor")
@RequiredArgsConstructor
public class AsmSnapshotController extends BaseController {

    private final AsmSnapshotService snapshotService;

    /**
     * 全部现存站房设备当前态快照（对齐 ADM unit gate）。
     *
     * @param unit 显示单位模式：{@code standard}（默认）→ 读 STANDARD purpose 行（seed 默认=native）；
     *             {@code custom} → 读 MONITOR purpose 行（用户偏好换算）。非 custom 的任意值（含缺省）
     *             一律按 standard 处理（与 ADM 同口径，无生产不映射旧值）。
     * @return AjaxResult.data = List&lt;{@link AsmSnapshotDeviceDto}&gt;（registry 无站房设备时空列表）
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:monitor:list')")
    @GetMapping("/snapshot")
    public AjaxResult snapshot(@RequestParam(value = "unit", required = false) String unit) {
        List<AsmSnapshotDeviceDto> cards = snapshotService.buildAll(unit);
        return AjaxResult.success(cards);
    }
}
