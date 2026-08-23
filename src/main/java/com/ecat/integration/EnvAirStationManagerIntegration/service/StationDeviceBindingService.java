package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigFlowService;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.FlowContext;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.FlowDriver;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.Operation;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.ProvisionResult;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.SchemaDtoConverter;
import com.ecat.integration.EnvAirStationManagerIntegration.driver.dto.FlowSchemaDto;
import com.ecat.integration.EnvAirStationManagerIntegration.lifecycle.AsmChangeRecordHook;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationProvisionProfile;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationProvisionProfileRegistry;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.logicdevice.StationLogicDeviceProfile;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.logicdevice.StationLogicDeviceProfileRegistry;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicMapping.IDeviceMapping;
import com.ecat.integration.logicdevice.LogicMapping.LogicMappingManager;
import com.ecat.integration.logicdevice.LogicdeviceIntegration;
import com.ecat.integration.logicdeviceairstation.AirstationIntegration;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 站房设备绑定服务（复刻 ADM AirDeviceBindingService，适配 ASM）。
 *
 * <p>编排：列类型槽及绑定（三态）/ 列厂家型号 / FlowDriver provision / submit 推进 config flow /
 * CREATE_ENTRY 原子收口（绑 logic station device + 旧设备按引用处理 + 失败回滚 + 审计）/ 移除。
 *
 * <p>与 ADM 的差异（ASM 适配定案）：
 * <ul>
 *   <li>槽粒度 = 整台设备（站房每槽一台物理设备，无 ADM 气象 attr 级多源）——绑定统一
 *       {@code provisionLogicFlow} 整台填 mapping.*。</li>
 *   <li>无 aggregation_mode 联动（ASM 物化模式与设备型号无关）。</li>
 *   <li>无状态参数 STORAGE seed——ASM 的 series seed 是数据驱动（AsmSeedService consumer 首见
 *       数据事件补 seed），绑定本身不触发 seed。</li>
 *   <li>审计落 {@code asm_device_change_record}（5 事件同 ADM）。</li>
 * </ul>
 *
 * <p>线程：DeviceRegistry 非线程安全，写操作按 ADM 同款方法级 synchronized 串行化。
 */
@Slf4j
@Service
public class StationDeviceBindingService {

    @Autowired
    private EcatCore core;

    /** 追溯钩子：由装配层（EnvAirStationManagerIntegration.onStart）注入；null 时 5 收口点跳过。 */
    @Setter
    private AsmChangeRecordHook changeRecordHook;

    /** 单例 FlowDriver（pendingFlows 是实例字段，provision/submit 须共享同一实例）。 */
    private FlowDriver flowDriver;

    private FlowDriver flowDriver() {
        if (flowDriver == null) {
            flowDriver = new FlowDriver(core.getConfigFlowService());
        }
        return flowDriver;
    }

    // ==================== 阶段0：列类型槽及绑定状态 ====================

    /**
     * 列 37 类型槽及当前绑定（GET /asm-monitor/device/params）。
     * 每槽：查所属 logic station device（getDeviceByUniqueId），读 mappings 中任一 device_id
     * （整台绑定语义：槽内全部 mapable attr 同一物理设备）。
     */
    public synchronized List<ParamBinding> listParamBindings() {
        List<ParamBinding> result = new ArrayList<>();
        AirstationIntegration station = airstationIntegration();
        for (StationParamMeta param : StationParamMeta.values()) {
            DeviceBase logicDevice = station == null ? null : station.getDeviceByUniqueId(param.getUniqueId());
            ConfigEntry logicEntry = core.getEntryRegistry() == null ? null
                    : core.getEntryRegistry().getByUniqueId(AirstationIntegration.COORDINATE, param.getUniqueId());
            String boundDeviceId = logicDevice == null ? null : firstBoundDeviceId(logicDevice);
            DeviceBase phyDevice = boundDeviceId == null ? null : core.getDeviceRegistry().getDeviceByID(boundDeviceId);
            DeviceState state = determineState(logicDevice, logicEntry, phyDevice);
            String coordinate = phyDevice != null && phyDevice.getEntry() != null
                    ? phyDevice.getEntry().getCoordinate() : null;
            String title = phyDevice != null && phyDevice.getEntry() != null
                    ? phyDevice.getEntry().getTitle() : null;
            result.add(new ParamBinding(param, state, boundDeviceId, coordinate, title));
        }
        return result;
    }

    /** 三态判定（纯函数，与 ADM 同构）。 */
    static DeviceState determineState(DeviceBase logicDevice, ConfigEntry logicEntry, DeviceBase phyDevice) {
        if (logicDevice != null && phyDevice != null) {
            return DeviceState.CONFIGURED;
        }
        if (logicDevice != null || logicEntry != null) {
            return DeviceState.UNBOUND;
        }
        return DeviceState.NOT_CREATED;
    }

    // ==================== 阶段1：列厂家型号 ====================

    public List<StationProvisionProfileRegistry.VendorOption> listVendors(StationParamMeta param) {
        return StationProvisionProfileRegistry.getInstance().getByType(param);
    }

    // ==================== 阶段2：provision ====================

    /** provision：driver 按 profile 快进身份步，停首个需用户输入步。COMPLETED 时 service 补原子收口。 */
    public synchronized ProvisionResult provision(StationParamMeta param, String coordinate, String model,
                                                  String sn, String name, Operation operation, String oldDeviceId) {
        StationProvisionProfile profile = StationProvisionProfileRegistry.getInstance()
                .get(param.deviceType, coordinate, model);
        if (profile == null) {
            // 严格模式：未注册的 profile 明确报错（厂商列表唯一来源=注册表，矩阵外不发明）
            throw new IllegalArgumentException("未注册的 profile: " + param.getType() + " / " + coordinate
                    + " / " + model + "（见 StationProfileRegistrar 注册矩阵）");
        }
        ProvisionResult pr = flowDriver().provision(profile, sn, name, operation, oldDeviceId);
        if (pr.getStatus() == ProvisionResult.Status.COMPLETED && pr.getSavedEntryId() != null) {
            finalizeAtomic(pr.getFlowId(), pr.getSavedEntryId());
        }
        return pr;
    }

    // ==================== 阶段3：submit 推进 + CREATE_ENTRY 原子收口 ====================

    /** 推进 config flow step；CREATE_ENTRY 那次触发原子收口。 */
    public synchronized SubmitResult submitFlowStep(String flowId, String stepId, Map<String, Object> userInput) {
        ConfigFlowService.ConfigFlowInstance inst = core.getConfigFlowService().submitStep(flowId, stepId, userInput);
        ConfigFlowResult.ResultType type = inst.getResult().getType();
        FlowContext ctx = flowDriver().getContext(flowId);
        if (type == ConfigFlowResult.ResultType.SHOW_FORM) {
            if (ctx != null && ctx.getOperation() != Operation.RECONFIGURE
                    && tryBindExistingDevice(ctx, inst.getFlow().getContext().getEntryData())) {
                core.getConfigFlowService().cancelFlow(flowId);
                flowDriver().removeContext(flowId);
                return new SubmitResult("CREATE_ENTRY", null, null, ctx.getReusedEntryId(), null, null);
            }
        }
        if (type == ConfigFlowResult.ResultType.CREATE_ENTRY) {
            String newEntryId = inst.getSavedEntryId();
            if (ctx != null && ctx.getOperation() == Operation.RECONFIGURE) {
                fireReconfigureAtCompletion(flowId);
            } else if (ctx != null) {
                finalizeAtomic(flowId, newEntryId);
            }
            return new SubmitResult("CREATE_ENTRY", null, null, newEntryId, null, null);
        }
        if (type == ConfigFlowResult.ResultType.SHOW_FORM) {
            return new SubmitResult("SHOW_FORM", inst.getStepId(),
                    SchemaDtoConverter.convert(inst.getResult().getSchema()), null,
                    inst.getResult().getStepInputs(), inst.getResult().getErrors());
        }
        flowDriver().removeContext(flowId);
        return new SubmitResult(type.name(), null, null, null, null, null);
    }

    /**
     * 原子收口：物理设备已建 → 驱动 StationDeviceConfigFlow 创建/重配 logic station device 并整台绑定
     * → REPLACE 旧物理按引用 removeEntry → 审计 → 任一失败 removeEntry 物理回滚。
     */
    private void finalizeAtomic(String flowId, String newEntryId) {
        FlowContext ctx = flowDriver().getContext(flowId);
        List<DeviceBase> newDevs = core.getDeviceRegistry().findDevicesByEntryId(newEntryId);
        if (newDevs.isEmpty()) {
            throw new IllegalStateException("CREATE_ENTRY 后找不到新物理设备: entryId=" + newEntryId);
        }
        String newDeviceId = newDevs.get(0).getId();
        try {
            bindLogicDevice(ctx.getStationParam(), newDeviceId);
            DeviceBase prevDeviceForRecord = null;
            if (ctx.getOperation() == Operation.REPLACE && ctx.getOldDeviceId() != null) {
                prevDeviceForRecord = core.getDeviceRegistry().getDeviceByID(ctx.getOldDeviceId());
                handleOldDeviceIfUnreferenced(ctx.getOldDeviceId());
            }
            if (changeRecordHook != null) {
                DeviceBase newDevice = newDevs.get(0);
                DeviceBase logicDevice = airstationIntegration() == null ? null
                        : airstationIntegration().getDeviceByUniqueId(ctx.getStationParam().getUniqueId());
                List<String> affectedAttrIds = logicDevice == null
                        ? emptyAffected(ctx.getStationParam()) : findAffectedAttrIds(logicDevice, newDeviceId);
                if (ctx.getOperation() == Operation.ADD) {
                    fireFirstBind(ctx.getStationParam(), logicDevice, newDevice, affectedAttrIds);
                } else {
                    fireReplace(ctx.getStationParam(), logicDevice, newDevice, prevDeviceForRecord, affectedAttrIds);
                }
            }
        } catch (RuntimeException e) {
            try {
                core.getEntryRegistry().removeEntry(newEntryId);
            } catch (Exception ignore) {
                e.addSuppressed(ignore);
            }
            throw e;
        } finally {
            flowDriver().removeContext(flowId);
        }
    }

    /** reconfigure（改连接）完成收口：仅落 RECONFIGURE 审计，不重建 logic 绑定。 */
    private void fireReconfigureAtCompletion(String flowId) {
        FlowContext ctx = flowDriver().getContext(flowId);
        try {
            if (changeRecordHook == null) {
                return;
            }
            StationParamMeta param = ctx.getStationParam();
            AirstationIntegration station = airstationIntegration();
            DeviceBase logicDevice = station == null ? null : station.getDeviceByUniqueId(param.getUniqueId());
            DeviceBase phy = core.getDeviceRegistry().getDeviceByID(ctx.getOldDeviceId());
            List<String> affectedAttrIds = logicDevice == null
                    ? emptyAffected(param) : findAffectedAttrIds(logicDevice, ctx.getOldDeviceId());
            fireReconfigure(param, logicDevice, phy, affectedAttrIds);
        } finally {
            flowDriver().removeContext(flowId);
        }
    }

    /**
     * 旧物理设备按引用处理（REPLACE/unbind/复用共用）：仍被<b>其他</b> logic attr 引用（含 ADM 分析仪/
     * 其他站房槽）→ 保留；否则 removeEntry（释放 uniqueId，下次同 uniqueId 经 getOrCreate 复原）。
     */
    /* package-private for testing */ void handleOldDeviceIfUnreferenced(String oldDeviceId) {
        LogicdeviceIntegration logic = logicdeviceIntegration();
        if (logic != null && !logic.findAffectedLogicAttrs(oldDeviceId).isEmpty()) {
            return;
        }
        DeviceBase oldDevice = core.getDeviceRegistry().getDeviceByID(oldDeviceId);
        if (oldDevice != null && oldDevice.getEntry() != null) {
            core.getEntryRegistry().removeEntry(oldDevice.getEntry().getEntryId());
        }
    }

    /**
     * uniqueId 已存在 active 设备 → 自动复用（跳过 provision，直接绑 logic station device）。
     * 每 step 监测：字段未齐 buildUniqueId 返 null 不预检。
     */
    /* package-private for testing */ boolean tryBindExistingDevice(FlowContext ctx, Map<String, Object> entryData) {
        StationProvisionProfile profile = ctx.getProfile();
        if (profile == null) {
            return false;
        }
        String uniqueId = profile.buildUniqueId(entryData);
        if (uniqueId == null) {
            return false;
        }
        ConfigEntry existing = core.getEntryRegistry().getByUniqueId(profile.getCoordinate(), uniqueId);
        if (existing == null || !existing.isEnabled()) {
            return false;
        }
        List<DeviceBase> devs = core.getDeviceRegistry().findDevicesByEntryId(existing.getEntryId());
        if (devs.isEmpty()) {
            return false;
        }
        String reusedDeviceId = devs.get(0).getId();
        bindLogicDevice(ctx.getStationParam(), reusedDeviceId);
        DeviceBase prevDeviceForRecord = null;
        if (ctx.getOperation() == Operation.REPLACE && ctx.getOldDeviceId() != null) {
            prevDeviceForRecord = core.getDeviceRegistry().getDeviceByID(ctx.getOldDeviceId());
            handleOldDeviceIfUnreferenced(ctx.getOldDeviceId());
        }
        if (changeRecordHook != null) {
            DeviceBase reusedDevice = devs.get(0);
            DeviceBase logicDevice = airstationIntegration() == null ? null
                    : airstationIntegration().getDeviceByUniqueId(ctx.getStationParam().getUniqueId());
            List<String> affectedAttrIds = logicDevice == null
                    ? emptyAffected(ctx.getStationParam()) : findAffectedAttrIds(logicDevice, reusedDeviceId);
            if (ctx.getOperation() == Operation.ADD) {
                fireRebind(ctx.getStationParam(), logicDevice, reusedDevice, affectedAttrIds);
            } else {
                fireReplace(ctx.getStationParam(), logicDevice, reusedDevice, prevDeviceForRecord, affectedAttrIds);
            }
        }
        ctx.setReusedEntryId(existing.getEntryId());
        return true;
    }

    /** 上一步。 */
    public synchronized SubmitResult previous(String flowId) {
        ConfigFlowService.ConfigFlowInstance inst = core.getConfigFlowService().goPrevious(flowId);
        ConfigFlowResult.ResultType type = inst.getResult().getType();
        if (type == ConfigFlowResult.ResultType.SHOW_FORM) {
            return new SubmitResult("SHOW_FORM", inst.getStepId(),
                    SchemaDtoConverter.convert(inst.getResult().getSchema()), null,
                    inst.getResult().getStepInputs(), inst.getResult().getErrors());
        }
        return new SubmitResult(type.name(), null, null, null, null, null);
    }

    // ==================== 移除（不走 flow）====================

    /**
     * 移除类型槽绑定（整台语义）：disable 整个 logic station device（清 bindingIndex + 持久化）→
     * 旧物理按引用 removeEntry → 落 UNBIND 审计。
     */
    public synchronized void unbind(StationParamMeta param) {
        AirstationIntegration station = airstationIntegration();
        if (station == null) {
            throw new IllegalStateException("AirstationIntegration 未加载");
        }
        DeviceBase logicDevice = station.getDeviceByUniqueId(param.getUniqueId());
        if (logicDevice == null) {
            return;
        }
        String oldDeviceId = firstBoundDeviceId(logicDevice);
        List<String> unbindAttrIds = oldDeviceId == null
                ? new ArrayList<>() : findAffectedAttrIds(logicDevice, oldDeviceId);
        DeviceBase oldPhyDevice = oldDeviceId == null ? null : core.getDeviceRegistry().getDeviceByID(oldDeviceId);
        logicdeviceIntegration().disable(logicDevice);
        if (oldDeviceId != null) {
            handleOldDeviceIfUnreferenced(oldDeviceId);
        }
        if (changeRecordHook != null && !unbindAttrIds.isEmpty()) {
            fireUnbind(param, logicDevice, oldPhyDevice, unbindAttrIds);
        }
    }

    // ==================== 辅助 ====================

    private AirstationIntegration airstationIntegration() {
        if (core == null) {
            return null;
        }
        return (AirstationIntegration) core.getIntegrationRegistry()
                .getIntegration(AirstationIntegration.COORDINATE);
    }

    private LogicdeviceIntegration logicdeviceIntegration() {
        if (core == null) {
            return null;
        }
        return (LogicdeviceIntegration) core.getIntegrationRegistry()
                .getIntegration("com.ecat:integration-logicdevice");
    }

    /** logic station device 的任一绑定 deviceId（整台语义：全部 mapable attr 同一物理设备；无绑定返 null）。 */
    @SuppressWarnings("unchecked")
    /* package-private for testing */ String firstBoundDeviceId(DeviceBase logicDevice) {
        if (logicDevice == null || logicDevice.getEntry() == null) {
            return null;
        }
        Map<String, Object> data = logicDevice.getEntry().getData();
        Map<String, Object> mappings = (Map<String, Object>) data.get("mappings");
        if (mappings == null) {
            return null;
        }
        for (Object v : mappings.values()) {
            if (v instanceof Map) {
                Object id = ((Map<String, Object>) v).get("device_id");
                if (id != null && !id.toString().isEmpty()) {
                    return id.toString();
                }
            }
        }
        return null;
    }

    /** 设备级兼容探测：vendor mapping 命中 (deviceType, coordinate, model) 即兼容（站房无 attr 级多源）。 */
    /* package-private for testing */ boolean deviceMatchesType(DeviceBase phy, String deviceType) {
        if (phy == null || phy.getEntry() == null) {
            return false;
        }
        LogicMappingManager mgr = LogicDevice.getLogicMappingManager();
        if (mgr == null) {
            return false;
        }
        return mgr.getMapping(deviceType, phy.getEntry().getCoordinate(),
                (String) phy.getEntry().getData().get("model")) != null;
    }

    /** 阶段②整台绑定：驱动 StationDeviceConfigFlow 把该 logic station device 全 mapable attr 绑到 phyId。 */
    /* package-private for testing */ void bindLogicDevice(StationParamMeta param, String phyId) {
        StationLogicDeviceProfile profile = StationLogicDeviceProfileRegistry.getInstance().get(param);
        flowDriver().provisionLogicFlow(profile, phyId);
    }

    // ==================== 详情 / 改连接 / 复用 ====================

    /** 详情：槽绑定设备的连接信息（entry.data 原样 dump）。 */
    public synchronized Map<String, Object> getDeviceDetail(StationParamMeta param) {
        AirstationIntegration station = airstationIntegration();
        if (station == null) {
            return null;
        }
        DeviceBase logicDevice = station.getDeviceByUniqueId(param.getUniqueId());
        if (logicDevice == null) {
            return null;
        }
        String boundDeviceId = firstBoundDeviceId(logicDevice);
        if (boundDeviceId == null) {
            return null;
        }
        DeviceBase phy = core.getDeviceRegistry().getDeviceByID(boundDeviceId);
        return phy == null || phy.getEntry() == null ? null : phy.getEntry().getData();
    }

    /** 改连接：对已绑设备启动 RECONFIGURE flow，返回 flowId + 首步 schema（完成后落 RECONFIGURE 审计）。 */
    public synchronized ReconfigureResult reconfigureConnection(StationParamMeta param) {
        AirstationIntegration station = airstationIntegration();
        if (station == null) {
            throw new IllegalStateException("AirstationIntegration 未加载");
        }
        DeviceBase logicDevice = station.getDeviceByUniqueId(param.getUniqueId());
        String boundDeviceId = logicDevice == null ? null : firstBoundDeviceId(logicDevice);
        if (boundDeviceId == null) {
            throw new IllegalStateException("类型槽 " + param.getType() + " 未绑定设备，无法改连接");
        }
        DeviceBase phy = core.getDeviceRegistry().getDeviceByID(boundDeviceId);
        if (phy == null || phy.getEntry() == null) {
            throw new IllegalStateException("已绑物理设备不存在: " + boundDeviceId);
        }
        ConfigFlowService.ConfigFlowInstance inst = core.getConfigFlowService()
                .startReconfigureFlow(phy.getEntry().getCoordinate(), phy.getEntry().getUniqueId());
        flowDriver().registerReconfigureContext(inst.getFlowId(), param, boundDeviceId);
        ConfigFlowResult result = inst.getResult();
        return new ReconfigureResult(
                inst.getFlowId(),
                result.getType() == ConfigFlowResult.ResultType.SHOW_FORM ? inst.getStepId() : null,
                SchemaDtoConverter.convert(result.getSchema()),
                result.getStepInputs());
    }

    /** 复用：列该类型槽兼容的已存在物理设备（设备级 vendor mapping 命中）。 */
    public synchronized List<CompatibleDevice> listCompatibleDevices(StationParamMeta param) {
        List<CompatibleDevice> result = new ArrayList<>();
        for (DeviceBase phy : core.getDeviceRegistry().getAllDevices()) {
            if (phy.getEntry() == null) {
                continue;
            }
            if (!deviceMatchesType(phy, param.deviceType)) {
                continue;
            }
            result.add(new CompatibleDevice(phy.getId(), phy.getEntry().getCoordinate(),
                    phy.getEntry().getTitle(), phy.getEntry().getUniqueId(),
                    referencingParams(phy.getId())));
        }
        return result;
    }

    /** 复用直绑：把已存在物理设备绑到类型槽（不走新 flow；兼容性双保险）。 */
    public synchronized void bindExisting(StationParamMeta param, String physicalDeviceId, String oldDeviceId) {
        DeviceBase phy = core.getDeviceRegistry().getDeviceByID(physicalDeviceId);
        if (phy == null || phy.getEntry() == null) {
            throw new IllegalArgumentException("物理设备不存在: " + physicalDeviceId);
        }
        if (!deviceMatchesType(phy, param.deviceType)) {
            throw new IllegalArgumentException("物理设备 " + phy.getEntry().getCoordinate() + "/"
                    + phy.getEntry().getData().get("model") + " 与类型槽 " + param.getType()
                    + " 不兼容（无该类型的 vendor mapping）");
        }
        bindLogicDevice(param, physicalDeviceId);
        DeviceBase prevDeviceForRecord = null;
        if (oldDeviceId != null && !oldDeviceId.equals(physicalDeviceId)) {
            prevDeviceForRecord = core.getDeviceRegistry().getDeviceByID(oldDeviceId);
            handleOldDeviceIfUnreferenced(oldDeviceId);
        }
        if (changeRecordHook != null) {
            AirstationIntegration station = airstationIntegration();
            DeviceBase logicDevice = station == null ? null : station.getDeviceByUniqueId(param.getUniqueId());
            List<String> affectedAttrIds = logicDevice == null
                    ? emptyAffected(param) : findAffectedAttrIds(logicDevice, physicalDeviceId);
            if (prevDeviceForRecord != null) {
                fireReplace(param, logicDevice, phy, prevDeviceForRecord, affectedAttrIds);
            } else {
                fireRebind(param, logicDevice, phy, affectedAttrIds);
            }
        }
    }

    /** 某物理设备被哪些类型槽引用。 */
    private List<String> referencingParams(String physicalDeviceId) {
        AirstationIntegration station = airstationIntegration();
        List<String> params = new ArrayList<>();
        if (station == null) {
            return params;
        }
        for (StationParamMeta p : StationParamMeta.values()) {
            DeviceBase logicDevice = station.getDeviceByUniqueId(p.getUniqueId());
            if (logicDevice != null
                    && physicalDeviceId.equals(firstBoundDeviceId(logicDevice))) {
                params.add(p.getType());
            }
        }
        return params;
    }

    // ==================== 审计钩子辅助 ====================

    /** logic device 缺失时的受影响 attr 占位（同 ADM：primaryAttrId 不可知，落空列表口径由调用方决定）。 */
    private static List<String> emptyAffected(StationParamMeta param) {
        return new ArrayList<>(Arrays.asList(param.getType()));
    }

    /** logic station device 中 device_id 指向某物理设备的全 mapable attrId（审计粒度）。 */
    @SuppressWarnings("unchecked")
    /* package-private for testing */ List<String> findAffectedAttrIds(DeviceBase logicDevice, String physicalDeviceId) {
        List<String> attrIds = new ArrayList<>();
        if (logicDevice == null || logicDevice.getEntry() == null || physicalDeviceId == null) {
            return attrIds;
        }
        Map<String, Object> data = logicDevice.getEntry().getData();
        Map<String, Object> mappings = (Map<String, Object>) data.get("mappings");
        if (mappings == null) {
            return attrIds;
        }
        for (Map.Entry<String, Object> e : mappings.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            Object id = ((Map<String, Object>) e.getValue()).get("device_id");
            if (id != null && physicalDeviceId.equals(id.toString())) {
                attrIds.add(e.getKey());
            }
        }
        return attrIds;
    }

    private static String vendorOf(DeviceBase phy) {
        return phy == null || phy.getEntry() == null ? null : phy.getEntry().getCoordinate();
    }

    private static Object rawModelOf(DeviceBase phy) {
        return phy == null || phy.getEntry() == null ? null : phy.getEntry().getData().get("model");
    }

    private static Object rawSnOf(DeviceBase phy) {
        return phy == null || phy.getEntry() == null ? null : phy.getEntry().getData().get("sn");
    }

    private static String strOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    private static String uniqueIdOf(DeviceBase phy) {
        return phy == null || phy.getEntry() == null ? null : phy.getEntry().getUniqueId();
    }

    private void fireFirstBind(StationParamMeta param, DeviceBase logicDevice, DeviceBase newPhy, List<String> attrIds) {
        changeRecordHook.onFirstBind(param.getUniqueId(), attrIds, uniqueIdOf(newPhy),
                vendorOf(newPhy), strOrNull(rawModelOf(newPhy)), strOrNull(rawSnOf(newPhy)),
                "首次绑定 " + vendorOf(newPhy) + "/" + strOrNull(rawModelOf(newPhy))
                        + " (uniqueId=" + uniqueIdOf(newPhy) + ") 到类型槽 " + param.label,
                operatorOf());
    }

    private void fireRebind(StationParamMeta param, DeviceBase logicDevice, DeviceBase reusedPhy, List<String> attrIds) {
        changeRecordHook.onRebind(param.getUniqueId(), attrIds, uniqueIdOf(reusedPhy),
                vendorOf(reusedPhy), strOrNull(rawModelOf(reusedPhy)), strOrNull(rawSnOf(reusedPhy)),
                "复用已存在设备 " + uniqueIdOf(reusedPhy) + " 绑定类型槽 " + param.label,
                operatorOf());
    }

    private void fireReplace(StationParamMeta param, DeviceBase logicDevice, DeviceBase newPhy,
                             DeviceBase prevPhy, List<String> attrIds) {
        changeRecordHook.onReplace(param.getUniqueId(), attrIds, uniqueIdOf(newPhy), uniqueIdOf(prevPhy),
                vendorOf(newPhy), strOrNull(rawModelOf(newPhy)), strOrNull(rawSnOf(newPhy)),
                "用新设备 " + uniqueIdOf(newPhy) + " 替换前设备 " + uniqueIdOf(prevPhy)
                        + " 绑定类型槽 " + param.label,
                operatorOf());
    }

    private void fireUnbind(StationParamMeta param, DeviceBase logicDevice, DeviceBase oldPhy, List<String> attrIds) {
        changeRecordHook.onUnbind(param.getUniqueId(), attrIds, uniqueIdOf(oldPhy),
                vendorOf(oldPhy), strOrNull(rawModelOf(oldPhy)), strOrNull(rawSnOf(oldPhy)),
                "类型槽 " + param.label + " 解绑设备 " + uniqueIdOf(oldPhy),
                operatorOf());
    }

    private void fireReconfigure(StationParamMeta param, DeviceBase logicDevice, DeviceBase phy, List<String> attrIds) {
        changeRecordHook.onReconfigure(param.getUniqueId(), attrIds, uniqueIdOf(phy),
                vendorOf(phy), strOrNull(rawModelOf(phy)), strOrNull(rawSnOf(phy)),
                "类型槽 " + param.label + " 改连接（设备 " + uniqueIdOf(phy) + "）",
                operatorOf());
    }

    /** 操作者：收口点无 ruoyi 用户上下文，落 "system"（与 ADM 同口径，controller 层可后置真实 operator）。 */
    private static String operatorOf() {
        return "system";
    }

    // ==================== DTO ====================

    /** 类型槽绑定状态（sidebar 三态用）。 */
    public static final class ParamBinding {
        public final StationParamMeta param;
        public final DeviceState state;
        public final boolean configured;
        public final String boundDeviceId;
        public final String coordinate;
        public final String title;

        public ParamBinding(StationParamMeta param, DeviceState state, String boundDeviceId,
                            String coordinate, String title) {
            this.param = param;
            this.state = state;
            this.configured = (state == DeviceState.CONFIGURED);
            this.boundDeviceId = boundDeviceId;
            this.coordinate = coordinate;
            this.title = title;
        }
    }

    /** 改连接启动结果（首步 schema + stepInputs 必须随 flowId 带出，前端渲染连接步）。 */
    public static final class ReconfigureResult {
        public final String flowId;
        public final String stepId;
        public final FlowSchemaDto schema;
        public final Map<String, Object> stepInputs;

        public ReconfigureResult(String flowId, String stepId, FlowSchemaDto schema, Map<String, Object> stepInputs) {
            this.flowId = flowId;
            this.stepId = stepId;
            this.schema = schema;
            this.stepInputs = stepInputs;
        }
    }

    /** submit/previous 推进结果。 */
    public static final class SubmitResult {
        public final String type;
        public final String stepId;
        public final FlowSchemaDto schema;
        public final String entryId;
        public final Map<String, Object> stepInputs;
        public final Map<String, Object> errors;

        public SubmitResult(String type, String stepId, FlowSchemaDto schema, String entryId,
                            Map<String, Object> stepInputs, Map<String, Object> errors) {
            this.type = type;
            this.stepId = stepId;
            this.schema = schema;
            this.entryId = entryId;
            this.stepInputs = stepInputs;
            this.errors = errors;
        }
    }

    /** 复用兼容设备（全稳定对象 API 来源，不按键义捞 entry.data）。 */
    public static final class CompatibleDevice {
        public final String deviceId;
        public final String coordinate;
        public final String title;
        public final String uniqueId;
        public final List<String> referencingParams;

        public CompatibleDevice(String deviceId, String coordinate, String title, String uniqueId,
                                List<String> referencingParams) {
            this.deviceId = deviceId;
            this.coordinate = coordinate;
            this.title = title;
            this.uniqueId = uniqueId;
            this.referencingParams = referencingParams;
        }
    }
}
