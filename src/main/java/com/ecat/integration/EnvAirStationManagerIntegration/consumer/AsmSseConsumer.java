package com.ecat.integration.EnvAirStationManagerIntegration.consumer;

import com.ecat.core.Bus.consumer.AbstractBusConsumer;
import com.ecat.core.Bus.event.DeviceDataChangedEvent;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDisplayRounder;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmDisplayValue;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import com.ecat.integration.EnvAirStationManagerIntegration.sse.AsmSseBroadcaster;
import com.ecat.integration.EnvAirStationManagerIntegration.sse.AsmSseEvent;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * ASM SSE 广播 consumer——订阅 device.data.update 总线，把站房逻辑设备（uid 前缀 logicdevice_station.*）
 * 的属性变化事件 per-event 经 {@link AsmSseEvent}（standard/custom 双值同推）序列化后由 {@link AsmSseBroadcaster#broadcast} 推给
 * 总览页瓦片墙。平移 ADM {@code AdmSseConsumer} 模式。
 *
 * <p><b>出口口径与 snapshot 一致</b>：数值经 {@link AsmUnitContract#resolveDisplay}（STANDARD+MONITOR 双 purpose）换算 +
 * {@link AsmUnitContract#unitSymbol} 符号化；displayName 来自 attr def（strings.json 中文，回退 attrId）；
 * statusName=主状态中文名（AttributeStatus.getDescription）。</p>
 *
 * <p><b>零在线连接短路</b>：broadcaster.activeCount()==0 直接返回，跳过序列化（无前端时不空转）。</p>
 */
@Slf4j
public class AsmSseConsumer extends AbstractBusConsumer<DeviceDataChangedEvent> {

    private static final String STATION_UID_PREFIX = "logicdevice_station.";

    private final DeviceRegistry registry;
    private final AsmSseBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final AsmUnitContract unitContract;
    private final AsmAlarmRegistry alarmRegistry;

    public AsmSseConsumer(String name, int capacity, DeviceRegistry registry,
                          AsmSseBroadcaster broadcaster, ObjectMapper objectMapper,
                          AsmUnitContract unitContract, AsmAlarmRegistry alarmRegistry) {
        super(name, capacity);
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.unitContract = unitContract;
        this.alarmRegistry = alarmRegistry;
    }

    @Override
    protected void consume(DeviceDataChangedEvent evt) {
        if (broadcaster.activeCount() == 0) {
            return;
        }
        DeviceBase device = registry.getDeviceByID(evt.getDeviceId());
        if (!(device instanceof LogicDevice)) {
            return;  // 物理设备事件：ASM 只广播站房逻辑设备
        }
        LogicDevice logic = (LogicDevice) device;
        String uid = logic.getUniqueId();
        if (uid == null || !uid.startsWith(STATION_UID_PREFIX)) {
            return;
        }
        AttrState<?> state = evt.getNewState();
        AsmSseEvent payload = buildEvent(uid, evt.getAttrId(), state, defIndex(logic).get(evt.getAttrId()),
                alarmRegistry.hasActive(uid));
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("ASM SSE 序列化 AsmSseEvent 失败：uid=" + uid
                    + " attrId=" + evt.getAttrId(), e);
        }
        broadcaster.broadcast(json);
    }

    /**
     * 组装单事件载荷（双模式双值同推（同 ADM 监控页方案））：custom 出口 = MONITOR 行换算 + HALF_EVEN 修约 +
     * 单位符号化；standard 出口 = STANDARD 行同口径独立换算。前端按 unit 模式取
     * displayValue/unit（custom）或 standardValue/standardUnit（standard）。非数值走 displayValue 串。
     */
    private AsmSseEvent buildEvent(String uid, String attrId, AttrState<?> state, LogicAttributeDefine def,
                                   boolean ruleAlarmActive) {
        String name = def != null ? def.getDisplayName() : attrId;
        Object value = state.getValue();
        if (value instanceof Number) {
            double raw = ((Number) value).doubleValue();
            String sourceKey = unitKey(state.getNativeUnit());
            Integer precision = def != null ? def.getDisplayPrecision() : null;
            AsmDisplayValue custom = unitContract.resolveDisplay(AsmUnitPurpose.MONITOR, uid, attrId, raw, sourceKey);
            AsmDisplayValue standard = unitContract.resolveDisplay(AsmUnitPurpose.STANDARD, uid, attrId, raw, sourceKey);
            return new AsmSseEvent(uid, attrId, name,
                    AsmDisplayRounder.round(custom.getValue(), precision),
                    AsmDisplayRounder.round(standard.getValue(), precision),
                    AsmUnitContract.unitSymbol(standard.getUnit()),
                    null,
                    AsmUnitContract.unitSymbol(custom.getUnit()),
                    lastUpdated(state), statusName(state), statusKey(state), ruleAlarmActive);
        }
        return new AsmSseEvent(uid, attrId, name, null, null, null, state.getDisplayValue(), null,
                lastUpdated(state), statusName(state), statusKey(state), ruleAlarmActive);
    }

    /** attrId → def 索引（displayName/展示精度来源；def.getDisplayName 缺省回退 attrId）。 */
    private static Map<String, LogicAttributeDefine> defIndex(LogicDevice device) {
        Map<String, LogicAttributeDefine> out = new HashMap<>();
        for (LogicAttributeDefine def : device.getAttrDefs()) {
            out.put(def.getAttrId(), def);
        }
        return out;
    }

    /** lastUpdated ISO-8601 串（null 安全——state 契约保证非 null，防御取 String.valueOf 语义）。 */
    private static String lastUpdated(AttrState<?> state) {
        return state.getLastUpdated() != null ? state.getLastUpdated().toString() : null;
    }

    private static String statusName(AttrState<?> state) {
        AttributeStatus status = state.getStatus();
        return status != null ? status.getDescription() : null;
    }

    /** 状态枚举 key（AttributeStatus.name()）——前端按枚举 key 着色，不按中文 description 串匹配。 */
    private static String statusKey(AttrState<?> state) {
        AttributeStatus status = state.getStatus();
        return status != null ? status.name() : null;
    }

    private static String unitKey(UnitInfo unit) {
        return unit != null ? unit.getFullUnitString() : null;
    }

    @Override
    protected void onConsumeError(DeviceDataChangedEvent event, RuntimeException error) {
        log.error("[诊断调试] ASM SSE 广播事件失败（worker 存活继续处理后续事件）：deviceId={} attrId={}：{}",
                event.getDeviceId(), event.getAttrId(), error.toString(), error);
    }
}
