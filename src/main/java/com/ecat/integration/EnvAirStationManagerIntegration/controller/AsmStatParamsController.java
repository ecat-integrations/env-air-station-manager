package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmStatParamRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * ASM 可查 stat 参数元数据（{@code GET /asm-monitor/stat-params}）——复用
 * {@link AirStationSdk#listStatParams()} 同一构建结果（asm_config_stat JOIN asm_config_unit(STORAGE)
 * 投影）逐行包装，不另造第二投影（端点与 Java SDK 永远同源）。
 *
 * <p><b>行追加历史数据页中文契约字段</b>（字段名固定）：{@code display_unit}（storageUnit full key →
 * core {@code UnitInfo.getDisplayName()} 显示串）与 {@code device_label}（槽中文名，
 * {@code AsmDeviceLabelService} 同源，多实例槽各自中文名）；SDK meta 本体平铺路径不变（纯追加）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/stat-params")
@RequiredArgsConstructor
public class AsmStatParamsController extends BaseController {

    private final AirStationSdk airStationSdk;
    private final AsmDeviceLabelService labelService;

    /** 参数候选池（历史页参数多选数据源；行=SDK meta 平铺 + display_unit/device_label）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:statParams:list')")
    @GetMapping
    public AjaxResult list() {
        // SDK 契约非 null（AirStationSdkImpl 恒返列表），null 直接 NPE 诚实暴露
        List<SdkParamMeta> metas = airStationSdk.listStatParams();
        List<AsmStatParamRowDto> rows = new ArrayList<>(metas.size());
        for (SdkParamMeta meta : metas) {
            rows.add(AsmStatParamRowDto.of(meta,
                    AsmUnitContract.unitDisplayName(meta.getStorageUnit()),
                    labelService.slotLabel(meta.getLogicDeviceUniqueId())));
        }
        return AjaxResult.success(rows);
    }
}
