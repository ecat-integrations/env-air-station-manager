package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.State.Unit.UnitInfoFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmControlRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlAction;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.sse.AsmControlCompletedEvent;
import com.ecat.integration.EnvAirStationManagerIntegration.sse.AsmSseBroadcaster;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.annotation.PreDestroy;

/**
 * ASM 统一控制服务（设计 §7）——<b>唯一控制收口</b>：REST（REMOTE）与 SDK（LOCAL）两路都汇入本服务，
 * 全程落 {@code asm_control_record} 审计。执行链路：
 *
 * <ol>
 *   <li><b>严格校验</b>：origin/caller/value 非空、uid 须 {@code logicdevice_station.} 前缀、
 *       设备与 attr 须存在（resolver 查 LogicDeviceManager）、attr 可写（{@code canValueChange()}），
 *       任一不满足明确抛（IAE/ISE），不静默兜底；</li>
 *   <li><b>before 快照</b>：执行前读一次 {@code attr.getState()}（不可变 AttrState，单次 volatile 读防撕裂），
 *       取 displayValue+unit 落 before_value；</li>
 *   <li><b>PENDING 先落</b>：审计行先 insert（回填 id），异步完成后回填——先有记录后有结果，
 *       任何路径（含超时）都留痕；</li>
 *   <li><b>专用 executor 执行</b>：{@code attr.setDisplayValue(value[, fromUnit])} 下发（Command 型经
 *       commandMapping 翻译到物理 bindAttr；带单位时按请求单位换算写入），不占总线线程；</li>
 *   <li><b>有限超时回读</b>：完成回读 after=新 AttrState 并回填 SUCCESS/FAILED + duration_ms；
 *       超时如实记 TIMEOUT（不猜结果），迟到完成不覆盖已回填终态（AtomicBoolean 一次性闸）。</li>
 * </ol>
 *
 * <p>executor / 超时调度 / 时钟全部注入（测试手动驱动，禁 sleep）。已知边界（设计 §7 注明）：
 * 绕过 ASM 直接打 core / logicdevice-api 的写操作不经本审计。</p>
 *
 * <p><b>调度登记声明（08 法表 I-4 B 处置）</b>：本服务是控制命令路径（{@code setDisplayValue}
 * 经 attr 写闸，治理已由 T12/F-10 吸收），controlExecutor/timeoutScheduler 为一次性任务
 * （非周期调度）硬理由保留自建——控制写有 10s 超时与审计语义，若与物化/告警共用 module:asm
 * 车道串行，慢物化会推迟控制执行制造伪 TIMEOUT；专用单线程 daemon 生命周期随模块，隔离即正确。</p>
 *
 * @author coffee
 */
@Service
public class AsmControlService {

    /** 超时调度抽象：生产用 ScheduledExecutorService，测试手动触发（禁 sleep）。 */
    public interface TimeoutScheduler {
        void schedule(Runnable task, Duration delay);
    }

    /** airstation 站房逻辑设备 uniqueId 前缀（与 consumer 同一判据）。 */
    private static final String STATION_UID_PREFIX = "logicdevice_station.";

    /** 默认执行超时（异步写完成回读上限；超时记 TIMEOUT 不猜）。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final Log log = LogFactory.getLogger(getClass());

    private final AsmControlRecordMapper recordMapper;
    /** uid → 站房逻辑设备（生产走 LogicDeviceManager；测试注入 map 桩）。 */
    private final Function<String, LogicDevice> stationResolver;
    /** 专用执行线程（不占总线线程）。 */
    private final Executor controlExecutor;
    private final TimeoutScheduler timeoutScheduler;
    /**
     * 生产路径的超时调度池本体（测试构造为 null——测试注入手动驱动的 TimeoutScheduler）：
     * @PreDestroy 统一关停用（见 {@link #shutdown}），与 controlExecutor 同属本服务
     * 自建执行资源（I-4 B 处置，见类 javadoc）。
     */
    private ScheduledExecutorService prodScheduler;
    private final Clock clock;
    private final Duration timeout;
    /** 控制终态帧广播（尽力而为通道，失败不得影响审计落库）。 */
    private final AsmSseBroadcaster broadcaster;
    /** 终态帧序列化（自拥裸 ObjectMapper，载荷无时间类型，同模块 consumer 模式）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 生产构造：自建单线程 executor + 定时超时调度 + 系统钟（字段直赋——this() 参数禁引 this）。 */
    @Autowired
    public AsmControlService(AsmControlRecordMapper recordMapper, AsmSseBroadcaster broadcaster) {
        this.recordMapper = recordMapper;
        this.stationResolver = AsmControlService::resolveStationDevice;
        this.controlExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "asm-control-executor");
            t.setDaemon(true);
            return t;
        });
        this.prodScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "asm-control-timeout");
            t.setDaemon(true);
            return t;
        });
        this.timeoutScheduler = (task, delay) ->
                prodScheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        this.clock = Clock.systemDefaultZone();
        this.timeout = DEFAULT_TIMEOUT;
        this.broadcaster = broadcaster;
    }

    /** 测试构造：全依赖注入（executor/调度器/时钟手动驱动）。 */
    AsmControlService(AsmControlRecordMapper recordMapper, Function<String, LogicDevice> stationResolver,
                      Executor controlExecutor, TimeoutScheduler timeoutScheduler, Clock clock,
                      Duration timeout, AsmSseBroadcaster broadcaster) {
        this.recordMapper = recordMapper;
        this.stationResolver = stationResolver;
        this.controlExecutor = controlExecutor;
        this.timeoutScheduler = timeoutScheduler;
        this.clock = clock;
        this.timeout = timeout;
        this.broadcaster = broadcaster;
    }

    /**
     * 停自建执行资源（@PreDestroy，jar 卸载期 Spring 容器调；镜像同包
     * {@link AsmSseBroadcaster#shutdown()} 的收口模式）。
     *
     * <p>E4-1 盘点处置：本服务两处自建池此前无任何停机路径（daemon 线程卸载后滞留）。
     * 未走 HostedExecutors/onRemove 绑定的原因：本类是动态 jar 的 Spring @Service，
     * 生命周期归 Spring 容器（@PreDestroy 即本类的停机 chokepoint），跨容器挂
     * ECAT 集成 onRemove 需要集成层 getSpringBean 接线（时序与归属待设计，列入
     * E4-1 待接线清单）；先以容器自身销毁回调收口。测试注入的 executor 若非
     * ExecutorService（如 {@code Runnable::run}）则跳过（测试无池可停）。
     */
    @PreDestroy
    public void shutdown() {
        if (controlExecutor instanceof ExecutorService) {
            ((ExecutorService) controlExecutor).shutdownNow();
        }
        if (prodScheduler != null) {
            prodScheduler.shutdownNow();
        }
    }

    /** 生产 resolver：LogicDeviceManager 全量站房设备中按 uniqueId 精确匹配（同 AirStationSdkImpl 模式）。 */
    private static LogicDevice resolveStationDevice(String uid) {
        for (LogicDevice device : LogicDeviceManager.getInstance().getRegisteredDevices()) {
            if (uid.equals(device.getUniqueId())) {
                return device;
            }
        }
        return null;
    }

    /**
     * 执行一次控制写（唯一收口；同步返回 PENDING 审计行，终态异步回填到同一对象——
     * 调用方持引用可直接读终态，或按 id 回查 asm_control_record）。
     *
     * @param origin   调用来源（LOCAL=本站/集成自身发起；REMOTE=第三方代传远程侧指令）
     * @param caller   严格非空：LOCAL=发起方集成坐标；REMOTE=最终用户标识
     * @param uid      站房逻辑设备 uniqueId（logicdevice_station.*）
     * @param attrId   属性 id（须存在且可写）
     * @param value    请求值（Command 型为选项 key）
     * @param fromUnit 请求值单位（null=不指定单位，按属性默认单位写入=无换算现状语义；
     *                 非空=按该单位换算写入，跨量纲失败属执行期失败→异步 FAILED）
     * @return 审计记录（id 已回填；result 终态异步回填）
     * @throws IllegalArgumentException origin/caller/value 空、uid 非站房前缀、设备或 attr 不存在
     * @throws IllegalStateException     attr 不可写（canValueChange=false）
     */
    public AsmControlRecord execute(AsmControlOrigin origin, String caller, String uid,
                                    String attrId, String value, UnitInfo fromUnit) {
        if (origin == null) {
            throw new IllegalArgumentException("origin 不能为空（LOCAL/REMOTE）");
        }
        if (caller == null || caller.trim().isEmpty()) {
            throw new IllegalArgumentException("caller 不能为空（REMOTE=认证 principal；LOCAL=消费方集成坐标）");
        }
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("value 不能为空");
        }
        if (uid == null || !uid.startsWith(STATION_UID_PREFIX)) {
            throw new IllegalArgumentException("uid 须为 airstation 站房逻辑设备（" + STATION_UID_PREFIX
                    + "* 前缀）: " + uid);
        }
        LogicDevice device = stationResolver.apply(uid);
        if (device == null) {
            throw new IllegalArgumentException("未知站房逻辑设备: " + uid);
        }
        AttributeBase<?> attr = device.getAttrs() == null ? null : device.getAttrs().get(attrId);
        if (attr == null) {
            throw new IllegalArgumentException("未知属性: uid=" + uid + " attrId=" + attrId);
        }
        if (!attr.canValueChange()) {
            throw new IllegalStateException("属性不可写（canValueChange=false）: uid=" + uid
                    + " attrId=" + attrId);
        }

        AttrState<?> beforeState = attr.getState();
        AsmControlRecord record = AsmControlRecord.builder()
                .origin(origin)
                .caller(caller)
                .logicDeviceUniqueId(uid)
                .attrId(attrId)
                .action(AsmControlAction.of(attr).name())
                .beforeValue(snapshot(beforeState))
                // 请求值留痕带单位口径（full string）——不带单位的行可与历史行零歧义区分
                .requestedValue(requestedValue(value, fromUnit))
                .result(AsmControlResult.PENDING)
                .build();
        recordMapper.insert(record);

        Instant start = clock.instant();
        AtomicBoolean finalized = new AtomicBoolean(false);

        controlExecutor.execute(() -> {
            try {
                CompletableFuture<Boolean> write = fromUnit == null
                        ? attr.setDisplayValue(value)
                        : attr.setDisplayValue(value, fromUnit);
                write.whenComplete((ok, ex) -> {
                    if (ex != null) {
                        finalizeOutcome(record, attr, finalized, start, AsmControlResult.FAILED,
                                ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    } else if (Boolean.TRUE.equals(ok)) {
                        finalizeOutcome(record, attr, finalized, start, AsmControlResult.SUCCESS, null);
                    } else {
                        finalizeOutcome(record, attr, finalized, start, AsmControlResult.FAILED,
                                "设备返回失败（setDisplayValue=false）");
                    }
                });
            } catch (RuntimeException e) {
                finalizeOutcome(record, attr, finalized, start, AsmControlResult.FAILED,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });

        timeoutScheduler.schedule(() ->
                        finalizeOutcome(record, attr, finalized, start, AsmControlResult.TIMEOUT, null),
                timeout);
        return record;
    }

    /** 终态一次性回填（超时/完成竞态谁先谁赢，迟到方直接返回不双写）；TIMEOUT 不回读 after（不猜结果）。 */
    private void finalizeOutcome(AsmControlRecord record, AttributeBase<?> attr, AtomicBoolean finalized,
                                 Instant start, AsmControlResult result, String error) {
        if (!finalized.compareAndSet(false, true)) {
            return;
        }
        record.setResult(result);
        record.setAfterValue(result == AsmControlResult.TIMEOUT ? null : snapshot(attr.getState()));
        record.setError(error);
        record.setDurationMs(Duration.between(start, clock.instant()).toMillis());
        recordMapper.updateResult(record);
        broadcastCompletion(record);
        if (result != AsmControlResult.SUCCESS) {
            log.warn("[诊断调试] 控制执行终态=" + result + " id=" + record.getId()
                    + " uid=" + record.getLogicDeviceUniqueId() + " attr=" + record.getAttrId()
                    + (error == null ? "" : " error=" + error));
        }
    }

    /**
     * 终态广播：审计落库后推 {@code control.completed} 具名帧——尽力而为通道，
     * 序列化/广播任何异常只记 debug 不上抛（不得影响审计；broadcaster 内部已自摘死连接，
     * 前端丢帧兜底=20s 超时 + 重连补偿单查）。
     */
    private void broadcastCompletion(AsmControlRecord record) {
        try {
            broadcaster.broadcastNamed(AsmControlCompletedEvent.TYPE,
                    objectMapper.writeValueAsString(new AsmControlCompletedEvent(
                            record.getId(), record.getLogicDeviceUniqueId(), record.getAttrId(),
                            record.getResult(), record.getError(), record.getAfterValue())));
        } catch (Exception e) {
            log.debug("[诊断调试] 控制终态帧广播失败（不影响审计落库）id=" + record.getId()
                    + "：" + e);
        }
    }

    /** AttrState 快照串：displayValue + 空格 + 单位（无量纲只存 displayValue；state 为 null 返 null）。 */
    private static String snapshot(AttrState<?> state) {
        if (state == null || state.getDisplayValue() == null) {
            return null;
        }
        return state.getNativeUnit() == null
                ? state.getDisplayValue()
                : state.getDisplayValue() + " " + state.getNativeUnit();
    }

    /** 审计请求值留痕串：带单位请求 = 「值 + 空格 + 单位 full string」（如 26.5 TemperatureUnit.CELSIUS）。 */
    private static String requestedValue(String value, UnitInfo fromUnit) {
        return fromUnit == null ? value : value + " " + fromUnit.getFullUnitString();
    }

    /**
     * 请求单位串 → {@link UnitInfo}（SDK/REST 两入口共用同一段解析，杜绝口径分叉）：
     * null/空白 = 不指定单位（返 null，按属性默认单位写入）；非空 = 单位 full string
     * 「枚举类名.枚举常量名」（如 {@code TemperatureUnit.CELSIUS}，{@code °C}/{@code mA} 等
     * 展示层符号禁用），非法抛 {@link IllegalArgumentException} 明确拒绝。
     *
     * <p>是否允许 null 由入口自行裁定（SDK 请求对象把 null 视为漏传须拒绝；REST 请求体字段缺省
     * 视为不指定）——本助手只承载「非空串怎么解析」这一段共性。</p>
     */
    public static UnitInfo parseFromUnit(String unit) {
        if (unit == null || unit.trim().isEmpty()) {
            return null;
        }
        try {
            return UnitInfoFactory.getEnum(unit.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unit 非法：须为单位 full string「枚举类名.枚举常量名」"
                    + "（如 TemperatureUnit.CELSIUS；°C/mA 等符号是展示层名称，禁止作传输值）: "
                    + unit + "，原因: " + e.getMessage());
        }
    }
}
