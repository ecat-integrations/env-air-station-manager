package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.driver.Operation;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.ProvisionResult;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationProvisionProfileRegistry;
import com.ecat.integration.EnvAirStationManagerIntegration.service.StationDeviceBindingService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站房设备配置管理 REST 端点（{@code /asm-monitor/device/*}，复刻 ADM AirDeviceController 的 11 端点）。
 *
 * <p>Ruoyi 内闭环：前端只调本前缀，config flow 驱动由 {@code /device/flow/*} 封装（内部调 ConfigFlowService）。
 * 返回统一经 ruoyi {@link AjaxResult} 包裹。
 *
 * <p><b>鉴权</b>（逐方法 {@link PreAuthorize}）：读类（params/vendors/deviceDetail/devices）→
 * {@code asm-monitor:device:list}；写类（provision/submit/previous/unbind/reconfigure/bind/replace）→
 * {@code asm-monitor:device:edit}。sys_menu 权限行见 {@code resources/sql/asm_auth.sql}（幂等 DO 块）。
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/device")
public class StationDeviceController extends BaseController {

    @Autowired
    private StationDeviceBindingService service;

    /** 37 类型槽及绑定状态（三态 CONFIGURED/UNBOUND/NOT_CREATED）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:list')")
    @GetMapping("/params")
    public AjaxResult listParams() {
        return AjaxResult.success(service.listParamBindings());
    }

    /**
     * 启动装载就绪信号（前端 station_device 页门控轮询源，语义复刻 ADM stat-config.initialized）。
     * data.initialized：airstation 集成的 createAllStationDevices 尾部完成点标志
     * （isStationDevicesCreated，零 entry 也置位）；airstation 集成未注册（禁用/core 未就绪）
     * → 恒 true——真未配置如实显示，不永久卡初始化骨架。前端据此区分「系统初始化中」与
     * 「真未配置」，杜绝零设备环境门控永不放行的空白页缺陷（bugs/bug-record-20260902-110219）。
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:list')")
    @GetMapping("/ready")
    public AjaxResult ready() {
        return AjaxResult.success(service.bootReady());
    }

    /** 该类型槽可选厂家型号。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:list')")
    @GetMapping("/params/{type}/vendors")
    public AjaxResult listVendors(@PathVariable String type) {
        return AjaxResult.success(service.listVendors(StationParamMeta.fromType(type)));
    }

    /**
     * provision（driver 快进身份步 + 停连接步）。
     * body: {coordinate, model, sn, name, operation:add|replace, oldDeviceId?}
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:edit')")
    @PostMapping("/params/{type}/provision")
    public AjaxResult provision(@PathVariable String type, @RequestBody Map<String, Object> body) {
        StationParamMeta param = StationParamMeta.fromType(type);
        String coordinate = (String) body.get("coordinate");
        String model = (String) body.get("model");
        String sn = (String) body.get("sn");
        String name = (String) body.get("name");
        Operation op = "replace".equalsIgnoreCase(String.valueOf(body.get("operation")))
                ? Operation.REPLACE : Operation.ADD;
        String oldDeviceId = body.get("oldDeviceId") == null ? null : String.valueOf(body.get("oldDeviceId"));
        return AjaxResult.success(service.provision(param, coordinate, model, sn, name, op, oldDeviceId));
    }

    /**
     * 推进 config flow step。body: {stepId, userInput:{fieldKey:value}}。CREATE_ENTRY 那次后端原子收口。
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:edit')")
    @PostMapping("/flow/{flowId}/submit")
    public AjaxResult submit(@PathVariable String flowId, @RequestBody Map<String, Object> body) {
        String stepId = (String) body.get("stepId");
        @SuppressWarnings("unchecked")
        Map<String, Object> userInput = (Map<String, Object>) body.get("userInput");
        return AjaxResult.success(service.submitFlowStep(flowId, stepId, userInput));
    }

    /** 上一步。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:edit')")
    @PostMapping("/flow/{flowId}/previous")
    public AjaxResult previous(@PathVariable String flowId) {
        return AjaxResult.success(service.previous(flowId));
    }

    /** 移除类型槽绑定。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:edit')")
    @PostMapping("/params/{type}/unbind")
    public AjaxResult unbind(@PathVariable String type) {
        service.unbind(StationParamMeta.fromType(type));
        return AjaxResult.success("已移除该类型槽绑定");
    }

    /** 详情：类型槽绑定设备连接信息（entry.data 原样 dump）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:list')")
    @GetMapping("/params/{type}/device")
    public AjaxResult deviceDetail(@PathVariable String type) {
        return AjaxResult.success(service.getDeviceDetail(StationParamMeta.fromType(type)));
    }

    /** 改连接：对已绑设备启动 RECONFIGURE flow，返回 flowId + 首步 schema。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:edit')")
    @PostMapping("/params/{type}/reconfigure")
    public AjaxResult reconfigure(@PathVariable String type) {
        return AjaxResult.success(service.reconfigureConnection(StationParamMeta.fromType(type)));
    }

    /** 复用：列该类型槽兼容的已存在物理设备。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:list')")
    @GetMapping("/params/{type}/devices")
    public AjaxResult compatibleDevices(@PathVariable String type) {
        return AjaxResult.success(service.listCompatibleDevices(StationParamMeta.fromType(type)));
    }

    /** 复用直绑。body: {physicalDeviceId, oldDeviceId?} */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:edit')")
    @PostMapping("/params/{type}/bind")
    public AjaxResult bind(@PathVariable String type, @RequestBody Map<String, Object> body) {
        String physicalDeviceId = String.valueOf(body.get("physicalDeviceId"));
        String oldDeviceId = body.get("oldDeviceId") == null ? null : String.valueOf(body.get("oldDeviceId"));
        service.bindExisting(StationParamMeta.fromType(type), physicalDeviceId, oldDeviceId);
        return AjaxResult.success("已绑定");
    }

    /** 更换：= provision(operation=replace)。body: {coordinate, model, sn, name, oldDeviceId}。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:device:edit')")
    @PostMapping("/params/{type}/replace")
    public AjaxResult replace(@PathVariable String type, @RequestBody Map<String, Object> body) {
        StationParamMeta param = StationParamMeta.fromType(type);
        String coordinate = (String) body.get("coordinate");
        String model = (String) body.get("model");
        String sn = (String) body.get("sn");
        String name = (String) body.get("name");
        String oldDeviceId = body.get("oldDeviceId") == null ? null : String.valueOf(body.get("oldDeviceId"));
        return AjaxResult.success(service.provision(param, coordinate, model, sn, name, Operation.REPLACE, oldDeviceId));
    }
}
