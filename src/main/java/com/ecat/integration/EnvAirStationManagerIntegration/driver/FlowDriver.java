package com.ecat.integration.EnvAirStationManagerIntegration.driver;

import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigFlowService;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.FlowContextConfig;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ImportFlowPayload;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationParamMeta;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.ImportFlowProfile;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationProvisionProfile;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.ProvisionStrategy;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.UserFlowProfile;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.logicdevice.StationLogicDeviceProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Config flow 驱动器（双策略 provision）。
 *
 * <p>按 profile 的 {@link StationProvisionProfile#getStrategy()} 分派两种 provision 机制（架构灵活适配两种集成 flow）：
 * <ul>
 *   <li><b>IMPORT_FLOW</b>（{@link ImportFlowProfile}）：profile 拼 import data →
 *       {@code startDiscoveryFlow(coordinate, IMPORT_FLOW, payload)}，集成自校验+预填+直达连接步（saimosen 落 protocol_select）。</li>
 *   <li><b>USER_FLOW</b>（{@link UserFlowProfile}）：
 *       启动 USER flow → 按身份步模板快进身份步 → 自动跳过欢迎/说明步（无 required-editable 字段即空提交）→
 *       停在第一个含 required 字段且非身份步的连接步。</li>
 * </ul>
 *
 * <p>USER_FLOW 自动跳过欢迎步根治「USER flow 首步是欢迎页导致 driver 卡死」bug。
 *
 * <p>业务上下文（stationParam/operation/oldDeviceId/profile）由本 driver 用 flowId→ctx Map 维护
 * （不塞 ConfigFlowService/FlowContext，避免污染物理 entry.data；仿 ImportFlowTestDriver）。
 *
 * <p><b>须单实例</b>：pendingFlows 是实例字段，provision/submit 须共享同一 driver（service 持单例）。
 *
 * @author coffee
 */
public class FlowDriver {

    /** USER_FLOW 快进上限（异常防护兜底，防 profile 与 flow 步序不匹配死循环）。 */
    private static final int USER_FLOW_STEP_GUARD = 30;

    private final ConfigFlowService configFlowService;
    private final ConcurrentHashMap<String, FlowContext> pendingFlows = new ConcurrentHashMap<>();

    public FlowDriver(ConfigFlowService configFlowService) {
        this.configFlowService = configFlowService;
    }

    /**
     * provision：按 profile 策略分派。
     *
     * @param profile 厂商 profile（自声明策略）
     * @param sn 用户填的 SN（无 SN 集成忽略）
     * @param name 用户填的设备名
     * @param operation ADD / REPLACE
     * @param oldDeviceId replace 时的旧设备 deviceId（add=null）
     * @return ProvisionResult（flowId + 停步 schema）
     */
    public ProvisionResult provision(StationProvisionProfile profile, String sn, String name,
                                     Operation operation, String oldDeviceId) {
        if (profile.getStrategy() == ProvisionStrategy.IMPORT_FLOW) {
            return provisionViaImportFlow((ImportFlowProfile) profile, sn, name, operation, oldDeviceId);
        }
        // USER_FLOW：profile 须是 UserFlowProfile（getStrategy=USER_FLOW 的唯一实现）。
        // sn/name 不入 USER flow——用户专属字段在 flow-form 填（profile identityInputs 只含 ADM 已知默认值）
        return provisionViaUserFlow((UserFlowProfile) profile,
                operation, oldDeviceId);
    }

    // ==================== IMPORT_FLOW 策略 ====================

    private ProvisionResult provisionViaImportFlow(ImportFlowProfile profile, String sn, String name,
                                                   Operation operation, String oldDeviceId) {
        ImportFlowPayload payload = new ImportFlowPayload(
                profile.getCoordinate(), profile.getImportVersion(), profile.buildImportData(sn, name));
        ConfigFlowService.ConfigFlowInstance inst = configFlowService.startDiscoveryFlow(
                profile.getCoordinate(), SourceType.IMPORT_FLOW, payload);
        String flowId = inst.getFlowId();
        pendingFlows.put(flowId, new FlowContext(profile.getStationParam(), operation, oldDeviceId, profile));
        return finishInit(flowId, inst);
    }

    // ==================== USER_FLOW 策略 ====================

    private ProvisionResult provisionViaUserFlow(UserFlowProfile profile,
                                                 Operation operation, String oldDeviceId) {
        // air-device-manager 开 last-writer-win：用户 abandon 后以同 SN 重试时，新 flow 取代泄漏的旧 pending flow
        // （setEntryUniqueId 遇同 uniqueId 的对手 active flow 时强制结束对方，而非抛异常卡 30 步）
        ConfigFlowService.ConfigFlowInstance inst = configFlowService.startFlow(profile.getCoordinate(),
                FlowContextConfig.builder().lastWriterWins(true).build());
        String flowId = inst.getFlowId();
        pendingFlows.put(flowId, new FlowContext(profile.getStationParam(), operation, oldDeviceId, profile));

        int guard = 0;
        while (inst.getResult() != null
                && inst.getResult().getType() == ConfigFlowResult.ResultType.SHOW_FORM) {
            if (++guard > USER_FLOW_STEP_GUARD) {
                // 失败兜底：cancel 自身 active flow，避免泄漏占 uniqueId 阻塞后续重试（防御纵深；
                // last-writer-win 已在 setEntryUniqueId 处理对手 flow，此处只清自身）
                abortOwnFlow(flowId);
                throw new IllegalStateException("USER_FLOW 快进超过 " + USER_FLOW_STEP_GUARD
                        + " 步，疑似 profile 与 flow 步序不匹配（或 uniqueId 冲突持续重显）: " + profile.getCoordinate());
            }
            String stepId = inst.getStepId();
            Map<String, String> identityTemplate = profile.getIdentityInputs().get(stepId);
            if (identityTemplate != null && !identityTemplate.isEmpty()) {
                // ADM hydrate 默认值：覆盖判停步（根治空值提交→校验失败→30 步循环）。
                // ADM 作为编排器+数据转换器，把已知默认值（分类 class/model、量程 range_mode 等）hydrate 进 flow；
                // 用户专属字段（sn/name）若未提供则视为"未覆盖"→停步交 flow-form 让用户补。
                // identityInputs 值为具体默认（迁移后无 {sn}/{name} 占位），直接拷贝即 hydrated。
                Map<String, Object> hydrated = new HashMap<>();
                hydrated.putAll(identityTemplate);
                List<String> uncovered = new ArrayList<>();
                for (String k : requiredEditableFieldKeys(inst.getResult().getSchema())) {
                    Object v = hydrated.get(k);
                    if (v == null || v.toString().trim().isEmpty()) {
                        uncovered.add(k);
                    }
                }
                if (uncovered.isEmpty()) {
                    // 默认值覆盖全部 required → 提交快进
                    inst = configFlowService.submitStep(flowId, stepId, hydrated);
                } else {
                    // 部分覆盖（sn/name 等用户专属未填）→ 预填非空默认 + 停步交 flow-form
                    Map<String, Object> prefill = new HashMap<>();
                    for (Map.Entry<String, Object> e : hydrated.entrySet()) {
                        if (e.getValue() != null && !e.getValue().toString().trim().isEmpty()) {
                            prefill.put(e.getKey(), e.getValue());
                        }
                    }
                    return stopForUserInputPrefilled(flowId, stepId, inst, prefill);
                }
            } else if (!hasRequiredEditableField(inst.getResult().getSchema())) {
                // 欢迎/说明步（无 required-editable 字段）：提交默认值自动跳过。
                // ConfigFlow 约定：空 userInput=status（重显当前步），非空=submit——故必须用 schema 默认值填非空，
                // 不能提交空 {}（会触发 stepXxx 的 isEmpty 分支重显→死循环）。
                Map<String, Object> adv = buildDefaultInput(inst.getResult().getSchema());
                if (adv.isEmpty()) {
                    // 无默认值的纯展示步：无法自动快进（不猜值），停下交前端渲染（前端 next 推进）
                    return stopForUserInput(flowId, stepId, inst);
                }
                inst = configFlowService.submitStep(flowId, stepId, adv);
            } else {
                // 连接步（含 required 字段且非身份步）：停下交前端
                return stopForUserInput(flowId, stepId, inst);
            }
            ConfigFlowResult.ResultType type = inst.getResult().getType();
            if (type == ConfigFlowResult.ResultType.CREATE_ENTRY) {
                // 身份步直接 CREATE_ENTRY（无连接步的集成）——schema 转 DTO(null)+savedEntryId 交 service finalize
                return ProvisionResult.builder()
                        .flowId(flowId).status(ProvisionResult.Status.COMPLETED)
                        .stoppedStepId(inst.getStepId()).schema(null)
                        .stepInputs(inst.getResult().getStepInputs())
                        .savedEntryId(inst.getSavedEntryId())
                        .build();
            }
            if (type == ConfigFlowResult.ResultType.ABORT) {
                pendingFlows.remove(flowId);
                throw new IllegalArgumentException("USER_FLOW 提交被集成拒绝: " + inst.getResult().getReason());
            }
        }
        abortOwnFlow(flowId);
        throw new IllegalStateException("USER_FLOW 意外结束，result type="
                + (inst.getResult() == null ? null : inst.getResult().getType()));
    }

    // ==================== 共用 ====================

    /** IMPORT_FLOW 触发后判终态：SHOW_FORM→停连接步；CREATE_ENTRY→COMPLETED；ABORT→抛异常报 reason。 */
    private ProvisionResult finishInit(String flowId, ConfigFlowService.ConfigFlowInstance inst) {
        ConfigFlowResult.ResultType type = inst.getResult().getType();
        if (type == ConfigFlowResult.ResultType.SHOW_FORM) {
            return stopForUserInput(flowId, inst.getStepId(), inst);
        }
        if (type == ConfigFlowResult.ResultType.CREATE_ENTRY) {
            return ProvisionResult.builder()
                    .flowId(flowId).status(ProvisionResult.Status.COMPLETED)
                    .stoppedStepId(inst.getStepId()).schema(null)
                    .stepInputs(inst.getResult().getStepInputs())
                    .savedEntryId(inst.getSavedEntryId())
                    .build();
        }
        pendingFlows.remove(flowId);
        if (type == ConfigFlowResult.ResultType.ABORT) {
            throw new IllegalArgumentException("provision IMPORT_FLOW 被集成拒绝: " + inst.getResult().getReason());
        }
        throw new IllegalStateException("provision IMPORT_FLOW 意外结束，result type=" + type);
    }

    /** 构造「停连接步交前端」结果：schema 转 DTO（剥 i18nProxy）+ stepInputs 水化。 */
    private ProvisionResult stopForUserInput(String flowId, String stepId, ConfigFlowService.ConfigFlowInstance inst) {
        return ProvisionResult.builder()
                .flowId(flowId).status(ProvisionResult.Status.NEED_USER_INPUT)
                .stoppedStepId(stepId)
                .schema(SchemaDtoConverter.convert(inst.getResult().getSchema()))
                .stepInputs(inst.getResult().getStepInputs())
                .build();
    }

    /** 构建 schema 默认值输入（用于自动跳过欢迎/说明步；只取有非 null 默认值的字段）。 */
    private Map<String, Object> buildDefaultInput(ConfigSchema schema) {
        Map<String, Object> input = new HashMap<>();
        if (schema == null) {
            return input;
        }
        List<AbstractConfigItem<?>> fields = schema.getFields();
        if (fields == null) {
            return input;
        }
        for (AbstractConfigItem<?> f : fields) {
            Object def = f.getDefaultValue();
            if (def != null) {
                input.put(f.getKey(), def);
            }
        }
        return input;
    }

    /** schema 是否含必填且可编辑字段（false=欢迎/说明步，可空提交跳过）。 */
    private boolean hasRequiredEditableField(ConfigSchema schema) {
        if (schema == null) {
            return false;
        }
        List<AbstractConfigItem<?>> fields = schema.getFields();
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        for (AbstractConfigItem<?> f : fields) {
            if (f.isRequired() && !f.isReadOnly()) {
                return true;
            }
        }
        return false;
    }

    /** schema 的必填且可编辑字段 key 列表（判断 ADM 默认值是否覆盖该步全部 required）。 */
    private List<String> requiredEditableFieldKeys(ConfigSchema schema) {
        List<String> keys = new ArrayList<>();
        if (schema == null) {
            return keys;
        }
        List<AbstractConfigItem<?>> fields = schema.getFields();
        if (fields == null) {
            return keys;
        }
        for (AbstractConfigItem<?> f : fields) {
            if (f.isRequired() && !f.isReadOnly()) {
                keys.add(f.getKey());
            }
        }
        return keys;
    }

    /**
     * 停步并把 ADM 已 hydrate 的默认值预填进 stepInputs（混合步用：分类默认值已填、sn/name 等用户专属待补）。
     * flow-form 读 step_inputs[stepId][field] 回显为字段默认值（FlowContext.stepInputs = stepId→userInput），
     * 故 prefill 须嵌套进 step_inputs[stepId]（合并该步已有 userInput），不能扁平放顶层——否则 flow-form 读不到、回落 schema defaultValue。
     */
    @SuppressWarnings("unchecked")
    private ProvisionResult stopForUserInputPrefilled(String flowId, String stepId,
                                                     ConfigFlowService.ConfigFlowInstance inst, Map<String, Object> prefill) {
        Map<String, Object> inputs = new HashMap<>();
        Map<String, Object> flowStepInputs = inst.getResult().getStepInputs();
        if (flowStepInputs != null) {
            inputs.putAll(flowStepInputs);
        }
        if (prefill != null && !prefill.isEmpty()) {
            Object existing = inputs.get(stepId);
            Map<String, Object> stepData = existing instanceof Map
                    ? new HashMap<>((Map<String, Object>) existing)
                    : new HashMap<>();
            stepData.putAll(prefill);
            inputs.put(stepId, stepData);
        }
        return ProvisionResult.builder()
                .flowId(flowId).status(ProvisionResult.Status.NEED_USER_INPUT)
                .stoppedStepId(stepId)
                .schema(SchemaDtoConverter.convert(inst.getResult().getSchema()))
                .stepInputs(inputs)
                .build();
    }

    /** 取 provision 阶段存的业务上下文（前端 submit 到 CREATE_ENTRY 时，收口用）。 */
    public FlowContext getContext(String flowId) {
        return pendingFlows.get(flowId);
    }

    /**
     * reconfigure flow 启动时注册业务上下文（与 provision 路径统一——provision 在 {@link #provision} 内注册，
     * reconfigure 不经 FlowDriver.provision，故由 service 显式调本方法注册）。
     *
     * <p>上下文用 {@link Operation#RECONFIGURE}，{@code deviceId} = 被改连接的物理设备 deviceId（复用 FlowContext
     * 的 oldDeviceId 字段——reconfigure 无「前设备」语义，此处置本次改连接的目标设备）。profile 传 null：
     * reconfigure 不是 StationProvisionProfile flow（无 profile），submitFlowStep 据 operation==RECONFIGURE 跳过
     * tryBindExistingDevice（其读 ctx.profile），故 profile=null 不会被解引用。
     *
     * <p>注册后 submitFlowStep CREATE_ENTRY 终态按 operation=RECONFIGURE 分流到 fireReconfigureAtCompletion，
     * 与 provision/replace 统一「flow 完成才落审计」（非启动即记）。
     */
    public void registerReconfigureContext(String flowId, StationParamMeta param, String deviceId) {
        pendingFlows.put(flowId, new FlowContext(param, Operation.RECONFIGURE, deviceId, null));
    }

    /** 收口完成/取消后清上下文。 */
    public void removeContext(String flowId) {
        pendingFlows.remove(flowId);
    }

    /**
     * 失败兜底：cancel 自身在 core ConfigFlowRegistry 的 active flow（释放 uniqueId 占用，防泄漏阻塞重试）
     * + 清本地 pendingFlows。provision 失败路径（30 步守卫 / 意外结束）调用。
     */
    private void abortOwnFlow(String flowId) {
        try {
            configFlowService.cancelFlow(flowId);
        } catch (Exception ignore) {
            // flow 可能已终态/不存在；忽略，避免掩盖原异常
        }
        pendingFlows.remove(flowId);
    }

    // ==================== 主题③：logic air device 创建 + 绑定（两阶段之 logic 阶段）====================

    /** LogicDeviceConfigFlow 步数上限（异常防护：select_type→attribute_mapping→final_confirm 三步，余量防异常 flow）。 */
    private static final int LOGIC_FLOW_STEP_GUARD = 10;

    /**
     * 驱动 LogicDeviceConfigFlow 创建 logic air device 并绑定物理设备（主题③ 两阶段之 logic 阶段，SINGLE_SOURCE 整台绑定）。
     *
     * <p>三步自动驱动（无用户交互——device_type/phyId/confirmed 均已知）：
     * {@code select_type}(device_type→设 uniqueId) → {@code attribute_mapping}(动态读 schema 填 mapping.*=phyId) →
     * {@code final_confirm}(confirmed=true) → CREATE_ENTRY。attribute_mapping 字段是 DynamicEnum（not-required），
     * 须主动填 phyId（否则 driver 会当无 required 步用默认设备）。
     *
     * <p>气体/PM(SINGLE_SOURCE)用此入口:一台分析仪绑该 logic device 全部 mapable attr。
     *
     * @param profile logic device profile（含 coordinate + device_type）
     * @param phyId   物理设备 deviceId（阶段①物理 flow CREATE_ENTRY 所得，填入 attribute_mapping）
     * @return logic device entryId（CREATE_ENTRY 后）
     * @throws RuntimeException logic flow 失败（ABORT/意外步/超步数）——调用方（finalizeAtomic）catch 后补偿回滚物理设备
     */
    public String provisionLogicFlow(StationLogicDeviceProfile profile, String phyId) {
        return driveLogicFlow(profile, schema -> fillMappingFromSchema(schema, phyId));
    }

    /**
     * 驱动 LogicDeviceConfigFlow 完成<strong>单 attr 级</strong>绑定（气象 MULTI_SOURCE,E 方案）。
     *
     * <p>attribute_mapping 步用 {@link #fillMappingForAttr}:目标 attr 置新 phyId,
     * <strong>兄弟 attr 读 schema 字段 {@code getDefaultValue()} 透传</strong>——CREATE 时默认值 null(仅目标 attr 有 phyId),
     * RECONFIGURE 时 flow 已把现有 mappings hydrate 成兄弟默认值(目标 attr 覆盖新 phyId、兄弟原样保留)。
     *
     * <p>兄弟保留的保证来自 driver 提交了完整状态(目标 attr 新值 + 兄弟 defaultValue),不依赖 flow 内部重建实现——
     * 只要 flow 仍接受 {@code mapping.<attrId>} 输入(flow 契约)即正确,防后期 flow 调整崩溃。
     *
     * @param profile logic device profile(气象走 LogicDevice-METEO)
     * @param attrId  目标逻辑属性 ID(如 wind_speed)
     * @param phyId   物理设备 deviceId
     * @return logic device entryId
     */
    public String provisionLogicFlowAttr(StationLogicDeviceProfile profile, String attrId, String phyId) {
        return driveLogicFlow(profile, schema -> fillMappingForAttr(schema, attrId, phyId));
    }

    /**
     * 驱动 LogicDeviceConfigFlow 建一个<strong>空白骨架</strong> logic device(全 attr device_id=null)。
     *
     * <p>用途:ADM {@code ensureLogicDeviceReadyForBind} 的 A 步 ensureExists——气象参数首次操作前预建骨架,
     * 统一入口条件(NOT_CREATED→存在+active),让下游 {@code supportsAttr(attrId, phy, logicDevice)} 拿到真实非空 logicDevice。
     * attribute_mapping 步提交空 map(不填任何 mapping.*)→ 全 attr 的 device_id=null → genAttrMap 走 phyDevice=null 分支建 Placeholder
     * (Placeholder 不发总线,VP3:零 ALARM 副作用)。
     *
     * <p>getOrCreate 语义由调用方保证(先 getDeviceByUniqueId 查后建,已存在不重建)。
     *
     * @param profile logic device profile
     * @return 新建空白 logic device entryId
     */
    public String provisionEmptyLogicDevice(StationLogicDeviceProfile profile) {
        return driveLogicFlow(profile, schema -> new HashMap<>());
    }

    /**
     * LogicDeviceConfigFlow 三步驱动共用骨架。attribute_mapping 步的输入由 mappingFiller 决定(整台填 / attr 级填 / 空骨架)。
     */
    private String driveLogicFlow(StationLogicDeviceProfile profile,
                                  Function<ConfigSchema, Map<String, Object>> mappingFiller) {
        ConfigFlowService.ConfigFlowInstance inst = configFlowService.startFlow(profile.getCoordinate());
        String flowId = inst.getFlowId();
        int guard = 0;
        while (inst.getResult() != null && inst.getResult().getType() == ConfigFlowResult.ResultType.SHOW_FORM) {
            if (++guard > LOGIC_FLOW_STEP_GUARD) {
                throw new IllegalStateException("LogicDeviceConfigFlow 快进超过 " + LOGIC_FLOW_STEP_GUARD + " 步: " + flowId);
            }
            String stepId = inst.getStepId();
            Map<String, Object> input;
            if ("select_type".equals(stepId)) {
                // StationDeviceConfigFlow 的 select_type：多实例类型还会要求 device_instance（实例选择子表单
                // 复用 select_type 步名提交）；profile 携带槽实例（StationParamMeta.instance），一并提交。
                if (profile.getInstance() != null) {
                    Map<String, Object> selectInput = new HashMap<>();
                    selectInput.put("device_type", profile.getDeviceType());
                    selectInput.put("device_instance", profile.getInstance());
                    input = selectInput;
                } else {
                    input = Collections.singletonMap("device_type", profile.getDeviceType());
                }
            } else if ("attribute_mapping".equals(stepId)) {
                input = mappingFiller.apply(inst.getResult().getSchema());
            } else if ("final_confirm".equals(stepId)) {
                input = Collections.singletonMap("confirmed", "true");
            } else {
                // 严格模式：profile 仅覆盖 logic flow 的三步，意外步 = flow 与 profile 不匹配，明确报错（不猜测）
                throw new IllegalStateException("LogicDeviceConfigFlow 意外步（profile 未覆盖）: " + stepId);
            }
            inst = configFlowService.submitStep(flowId, stepId, input);
            ConfigFlowResult.ResultType type = inst.getResult().getType();
            if (type == ConfigFlowResult.ResultType.CREATE_ENTRY) {
                return inst.getSavedEntryId();
            }
            if (type == ConfigFlowResult.ResultType.ABORT) {
                throw new IllegalArgumentException("LogicDeviceConfigFlow ABORT: " + inst.getResult().getReason());
            }
        }
        throw new IllegalStateException("LogicDeviceConfigFlow 未达 CREATE_ENTRY（终态: "
                + (inst.getResult() == null ? null : inst.getResult().getType()) + ")");
    }

    /** attribute_mapping 步(SINGLE):读 schema 的 mapping.* 字段，全部填 phyId（绑定物理设备到该参数 logic device 的所有 attr）。 */
    private Map<String, Object> fillMappingFromSchema(ConfigSchema schema, String phyId) {
        Map<String, Object> input = new HashMap<>();
        if (schema != null && schema.getFields() != null) {
            for (AbstractConfigItem<?> f : schema.getFields()) {
                if (f.getKey() != null && f.getKey().startsWith("mapping.")) {
                    input.put(f.getKey(), phyId);
                }
            }
        }
        return input;
    }

    /**
     * attribute_mapping 步(MULTI,E 方案):目标 attr 置新 phyId,兄弟 attr 读 schema 字段 {@code getDefaultValue()} 透传。
     *
     * <p>CREATE 时 context.mappings 空→schema 字段默认值 null→仅目标 attr 有 phyId;
     * RECONFIGURE 时 LogicDeviceConfigFlow 已把现有 mappings hydrate 为字段默认值→兄弟透传原绑定、目标 attr 覆盖新 phyId。
     * driver 不碰 entry.data,当前配置从 flow 自身 schema 读。
     *
     * @throws IllegalStateException 目标 attr 不在该 logic device 的 mapping schema(调用方传错 attrId/mappingType)
     */
    private Map<String, Object> fillMappingForAttr(ConfigSchema schema, String attrId, String phyId) {
        Map<String, Object> input = new HashMap<>();
        String targetKey = "mapping." + attrId;
        boolean targetPresent = false;
        if (schema != null && schema.getFields() != null) {
            for (AbstractConfigItem<?> f : schema.getFields()) {
                String key = f.getKey();
                if (key != null && key.startsWith("mapping.")) {
                    if (key.equals(targetKey)) {
                        input.put(key, phyId);
                        targetPresent = true;
                    } else {
                        // 兄弟 attr 透传:CREATE=null,RECONFIGURE=flow hydrate 的当前 device_id
                        input.put(key, f.getDefaultValue());
                    }
                }
            }
        }
        if (!targetPresent) {
            // 严格模式:目标 attr 不在 mapping schema = 调用方传错,明确报错(不静默建空 mapping)
            throw new IllegalStateException("目标 attr 不在 logic device mapping schema: " + targetKey);
        }
        return input;
    }
}

