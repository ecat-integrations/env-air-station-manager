package com.ecat.integration.EnvAirStationManagerIntegration.consumer;

import com.ecat.core.Bus.consumer.AbstractBatchBusConsumer;
import com.ecat.core.Bus.event.DeviceDataChangedEvent;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.NumberAttribute;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmDataSampleMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSeedService;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ASM raw 落库 consumer——订阅 device.data.update 总线，攒批把 airstation 站房逻辑设备的采样
 * 写入 asm_data_sample hypertable（机制同 ADM AdmDataSampleConsumer，ASM 自建）。
 *
 * <p><b>前置过滤</b>：仅 {@code logicdevice_station.*} 前缀的 {@link LogicDevice} 事件入 samples——
 * 物理设备事件 / ADM 域分析仪（logicdevice.*）/ 孤儿事件跳过（uid 前缀过滤，不依赖设备类型枚举——
 * 前缀即 airstation 域单一判据，与 {@link AsmSeedService} seedStartup 同口径）。</p>
 *
 * <p><b>攒批</b>：基类 {@link AbstractBatchBusConsumer} batchSize 满 / flushIntervalMs 超时二选一触发
 * {@link #flush}（独占 worker 线程 + drop-oldest 反压）；整批全被过滤跳过不调 mapper（防 foreach 空
 * collection 非法 SQL）。flush 抛 RuntimeException 由基类兜转 {@link #onFlushError}（计数 + log，不杀线程）。</p>
 *
 * <p><b>首见 seed</b>：numeric attr 的 series 首次出现时调 {@link AsmSeedService#seedSeries}（本 consumer
 * 进程内 seen set 去重；非 numeric 不 seed——avg-only 引擎只物化 numeric，与 seedStartup 同口径）。
 * 启动后用户新建设备的配置行由本路自然补齐（seed 幂等，冲突让路人工配置）。</p>
 *
 * <p><b>值分槽</b>：Number → valueNum（BigDecimal.toString 保精度）、String → valueText、其他两 null
 * （严格模式不猜转换）。dataTime = state.lastUpdated（Instant 绝对时刻直存 timestamptz；null 抛——
 * 设备数据事件契约必带，null 是上游契约破坏须显式暴露非静默）。</p>
 *
 * @author coffee
 */
public class AsmDataSampleConsumer extends AbstractBatchBusConsumer<DeviceDataChangedEvent> {

    /** airstation 逻辑设备 uniqueId 前缀（与 AsmSeedService 同一判据）。 */
    private static final String STATION_UID_PREFIX = "logicdevice_station.";

    private final DeviceRegistry registry;
    private final AsmDataSampleMapper sampleMapper;
    private final AsmSeedService seedService;

    /** 本 consumer 已 seed 的 series（uid:attrId）——首见触发，之后热路径零 seed 判定成本。 */
    private final Set<String> seededSeries = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** DB 写失败累计丢弃条数（onFlushError 周期诊断，运维定位 hypertable 故障窗口）。 */
    private final AtomicLong droppedOnFlushError = new AtomicLong();

    public AsmDataSampleConsumer(String name, int capacity, int batchSize, long flushIntervalMs,
                                 DeviceRegistry registry, AsmDataSampleMapper sampleMapper,
                                 AsmSeedService seedService) {
        super(name, capacity, batchSize, flushIntervalMs);
        this.registry = registry;
        this.sampleMapper = sampleMapper;
        this.seedService = seedService;
    }

    @Override
    protected void flush(List<DeviceDataChangedEvent> batch) {
        List<AsmDataSample> samples = new ArrayList<>(batch.size());
        for (DeviceDataChangedEvent evt : batch) {
            DeviceBase device = registry.getDeviceByID(evt.getDeviceId());
            if (!(device instanceof LogicDevice)) {
                continue;  // 物理事件 / 孤儿事件（device==null 时 instanceof false）
            }
            LogicDevice logic = (LogicDevice) device;
            String uid = logic.getUniqueId();
            if (uid == null || !uid.startsWith(STATION_UID_PREFIX)) {
                continue;  // 非 airstation 站房逻辑设备（ADM 分析仪等）
            }
            samples.add(toSample(uid, evt.getAttrId(), evt.getNewState()));
            seedIfFirstSeen(logic, uid, evt.getAttrId());
        }
        if (samples.isEmpty()) {
            return;  // 整批全跳过，不调 mapper
        }
        sampleMapper.batchInsert(samples);
    }

    /** 首见 numeric series 触发 seed（进程内 seen 去重；seedSeries 自身另有 DB 侧幂等）。 */
    private void seedIfFirstSeen(LogicDevice logic, String uid, String attrId) {
        AttributeBase<?> attr = logic.getAttrs().get(attrId);
        if (!(attr instanceof NumberAttribute)) {
            return;
        }
        String series = uid + ":" + attrId;
        if (!seededSeries.add(series)) {
            return;
        }
        UnitInfo nativeUnit = attr.getNativeUnit();
        seedService.seedSeries(uid, attrId, nativeUnit == null ? "" : nativeUnit.getFullUnitString());
    }

    /** AttrState → AsmDataSample 规范化构造（值分槽 / Instant 直存 / unit enum 全 key 形式）。 */
    private static AsmDataSample toSample(String uid, String attrId, AttrState<?> state) {
        Object value = state.getValue();
        BigDecimal valueNum = (value instanceof Number) ? new BigDecimal(value.toString()) : null;
        String valueText = (value instanceof String) ? (String) value : null;
        Instant dataTime = state.getLastUpdated();
        if (dataTime == null) {
            // 设备数据事件契约必带 lastUpdated；null 是上游契约破坏，显式暴露非静默落 null（DDL data_time NOT NULL）
            throw new IllegalStateException(
                    "[诊断调试] airstation 数据事件缺 lastUpdated: uid=" + uid + " attrId=" + attrId);
        }
        UnitInfo unit = state.getUnit();
        String unitName = unit == null ? null : unit.getFullUnitString();
        if (unitName != null && unitName.isEmpty()) {
            unitName = null;
        }
        return AsmDataSample.builder()
                .logicDeviceUniqueId(uid)
                .attrId(attrId)
                .dataTime(dataTime)
                .valueNum(valueNum)
                .valueText(valueText)
                .unit(unitName)
                .source("POLL")
                .build();
    }

    /** DB 写失败兜底（基类 flushBuffer catch 后转本回调，不杀 worker）：计数 + 诊断 log，不再抛。 */
    @Override
    protected void onFlushError(List<DeviceDataChangedEvent> batch, RuntimeException error) {
        long dropped = droppedOnFlushError.addAndGet(batch.size());
        log.error("[诊断调试] asm_data_sample 批量写失败，丢弃 {} 条（累计 {}）；首 deviceId={} attrId={}: ",
                dropped,
                batch.isEmpty() ? "-" : batch.get(0).getDeviceId(),
                batch.isEmpty() ? "-" : batch.get(0).getAttrId(),
                error);
    }
}
