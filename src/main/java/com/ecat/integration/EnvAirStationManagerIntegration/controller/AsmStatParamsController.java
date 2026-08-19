package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ASM 可查 stat 参数元数据（{@code GET /asm-monitor/stat-params}）——直接透传
 * {@link AirStationSdk#listStatParams()} 同一构建结果（asm_config_stat JOIN asm_config_unit(STORAGE)
 * 投影），不另造第二投影（端点与 Java SDK 永远同源）。
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/stat-params")
@RequiredArgsConstructor
public class AsmStatParamsController extends BaseController {

    private final AirStationSdk airStationSdk;

    /** 参数候选池（历史页参数多选数据源）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:statParams:list')")
    @GetMapping
    public AjaxResult list() {
        return AjaxResult.success(airStationSdk.listStatParams());
    }
}
