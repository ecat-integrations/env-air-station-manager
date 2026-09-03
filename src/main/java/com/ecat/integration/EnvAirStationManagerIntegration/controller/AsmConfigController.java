package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmConfigStatRowDto;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmConfigService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDeviceLabelService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * ASM 配置读写端点（config_stat 聚合配置 + config_unit 单位偏好），校验/缓存失效收口在
 * {@link AsmConfigService}。
 *
 * <p><b>语义</b>：写 config_stat 不回溯历史（下个物化 tick 按新配置走，不触发重算）——PUT 响应
 * msg 注明；写 config_unit 即时生效（service 已失效单位缓存）。</p>
 *
 * <p><b>config-stat 行追加契约字段</b>（前端 ruoyi 化，字段名固定）：{@code device_label}（槽中文）/
 * {@code attr_label}（attr 中文 displayName）——口径与 alarm-record 行同源
 * （{@link AsmDeviceLabelService}），旧字段平铺路径不变。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor")
@RequiredArgsConstructor
public class AsmConfigController extends BaseController {

    private final AsmConfigService configService;
    private final AsmDeviceLabelService labelService;

    /** 聚合配置行（GET /asm-monitor/config-stat；行=config 字段平铺 + device_label/attr_label）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:config:read')")
    @GetMapping("/config-stat")
    public AjaxResult getStat() {
        List<AsmConfigStatRowDto> rows = new ArrayList<>();
        for (AsmConfigStat stat : configService.listStatConfig()) {
            rows.add(AsmConfigStatRowDto.of(stat,
                    labelService.slotLabel(stat.getLogicDeviceUniqueId()),
                    labelService.attrLabel(stat.getLogicDeviceUniqueId(), stat.getAttrId())));
        }
        return AjaxResult.success(rows);
    }

    /** 改单 series 聚合配置（PUT /asm-monitor/config-stat；operator=认证 principal）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:config:edit')")
    @PutMapping("/config-stat")
    public AjaxResult putStat(@RequestBody ConfigStatRequest request) {
        return putStatForCaller(request, SecurityUtils.getUsername());
    }

    /** principal 注入点拆开（单测免静态 mock 直接验收口契约）。 */
    AjaxResult putStatForCaller(ConfigStatRequest request, String caller) {
        if (request == null) {
            throw new IllegalArgumentException("配置请求体为空");
        }
        AsmConfigStat saved = configService.updateStatConfig(request.getLogicDeviceUniqueId(),
                request.getAttrId(), request.getEnabled(), request.getGranularityMask(),
                request.getMaterializationMode(), caller);
        return AjaxResult.success("配置已保存：下个物化 tick 按新配置生效（配置不回溯历史，不触发重算）", saved);
    }

    /** 单位偏好行 MONITOR/HISTORY（GET /asm-monitor/config-unit）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:config:read')")
    @GetMapping("/config-unit")
    public AjaxResult getUnit() {
        List<AsmConfigUnit> rows = configService.listUnitPrefs();
        return AjaxResult.success(rows);
    }

    /** 写单条单位偏好（PUT /asm-monitor/config-unit；写后即时生效，缓存已失效）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:config:edit')")
    @PutMapping("/config-unit")
    public AjaxResult putUnit(@RequestBody ConfigUnitRequest request) {
        return putUnitForCaller(request, SecurityUtils.getUsername());
    }

    /** principal 注入点拆开（单测免静态 mock 直接验收口契约）。 */
    AjaxResult putUnitForCaller(ConfigUnitRequest request, String caller) {
        if (request == null) {
            throw new IllegalArgumentException("配置请求体为空");
        }
        AsmConfigUnit saved = configService.updateUnitPref(request.getLogicDeviceUniqueId(),
                request.getAttrId(), request.getPurpose(), request.getUnit(),
                request.getDisplayPrecision(), caller);
        return AjaxResult.success("单位偏好已保存（读出口缓存已失效，即时生效）", saved);
    }

    /** config_stat 请求体。 */
    public static class ConfigStatRequest {
        private String logicDeviceUniqueId;
        private String attrId;
        private Boolean enabled;
        private Integer granularityMask;
        private String materializationMode;

        public ConfigStatRequest() {
        }

        public ConfigStatRequest(String logicDeviceUniqueId, String attrId, Boolean enabled,
                                 Integer granularityMask, String materializationMode) {
            this.logicDeviceUniqueId = logicDeviceUniqueId;
            this.attrId = attrId;
            this.enabled = enabled;
            this.granularityMask = granularityMask;
            this.materializationMode = materializationMode;
        }

        public String getLogicDeviceUniqueId() { return logicDeviceUniqueId; }
        public void setLogicDeviceUniqueId(String v) { this.logicDeviceUniqueId = v; }
        public String getAttrId() { return attrId; }
        public void setAttrId(String v) { this.attrId = v; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean v) { this.enabled = v; }
        public Integer getGranularityMask() { return granularityMask; }
        public void setGranularityMask(Integer v) { this.granularityMask = v; }
        public String getMaterializationMode() { return materializationMode; }
        public void setMaterializationMode(String v) { this.materializationMode = v; }
    }

    /** config_unit 请求体。 */
    public static class ConfigUnitRequest {
        private String logicDeviceUniqueId;
        private String attrId;
        private String purpose;
        private String unit;
        private Integer displayPrecision;

        public ConfigUnitRequest() {
        }

        public ConfigUnitRequest(String logicDeviceUniqueId, String attrId, String purpose, String unit) {
            this.logicDeviceUniqueId = logicDeviceUniqueId;
            this.attrId = attrId;
            this.purpose = purpose;
            this.unit = unit;
        }

        public String getLogicDeviceUniqueId() { return logicDeviceUniqueId; }
        public void setLogicDeviceUniqueId(String v) { this.logicDeviceUniqueId = v; }
        public String getAttrId() { return attrId; }
        public void setAttrId(String v) { this.attrId = v; }
        public String getPurpose() { return purpose; }
        public void setPurpose(String v) { this.purpose = v; }
        public String getUnit() { return unit; }
        public void setUnit(String v) { this.unit = v; }
        public Integer getDisplayPrecision() { return displayPrecision; }
        public void setDisplayPrecision(Integer v) { this.displayPrecision = v; }
    }
}
