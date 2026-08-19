package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.NumberAttribute;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmControlRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制服务单测——手动驱动 executor / 超时调度器 / 时钟（零 sleep，确定性时序）：
 * PENDING 先落 → 执行回填 SUCCESS/FAILED；超时回填 TIMEOUT 且迟到执行不双写；
 * before/after 取真实 AttrState（builder 构造，禁 mock final）；非法入参/不可写明确抛。
 */
@ExtendWith(MockitoExtension.class)
class AsmControlServiceTest {

    /** 可实例化可写数值属性：覆写 setDisplayValue/getState，行为注入（成功/失败/挂起）。 */
    static class WritableAttr extends NumberAttribute<Double> {
        interface WriteBehavior {
            CompletableFuture<Boolean> write(String value, WritableAttr self);
        }

        private AttrState<?> state;
        final WriteBehavior behavior;

        WritableAttr(String attrId, boolean changeable, AttrState<?> initial, WriteBehavior behavior) {
            super(attrId, AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                    1, false, changeable);
            this.state = initial;
            this.behavior = behavior;
        }

        @Override
        protected Double convertToType(double value) {
            return value;
        }

        @Override
        public CompletableFuture<Boolean> setDisplayValue(String newDisplayValue) {
            return behavior.write(newDisplayValue, this);
        }

        @SuppressWarnings("unchecked")
        @Override
        public AttrState<Double> getState() {
            return (AttrState<Double>) state;
        }

        void setState(AttrState<?> s) {
            this.state = s;
        }
    }

    /** 手动驱动的执行器：execute 只登记，测试显式 runPending() 触发（不 sleep）。 */
    static class ManualExecutor implements Executor {
        final List<Runnable> pending = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runAll() {
            List<Runnable> snapshot = new ArrayList<>(pending);
            pending.clear();
            snapshot.forEach(Runnable::run);
        }
    }

    /** 手动驱动的超时调度器：登记 (task)，测试显式 fire() 触发超时路径。 */
    static class ManualTimeoutScheduler implements AsmControlService.TimeoutScheduler {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay) {
            tasks.add(task);
        }

        void fireAll() {
            List<Runnable> snapshot = new ArrayList<>(tasks);
            tasks.clear();
            snapshot.forEach(Runnable::run);
        }
    }

    /** 可推进时钟（禁 sleep 同步：duration_ms 断言依赖确定性时间源）。 */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-08-18T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private AsmControlRecordMapper recordMapper;
    @Mock
    private LogicDevice stationDevice;

    private final ManualExecutor executor = new ManualExecutor();
    private final ManualTimeoutScheduler timeoutScheduler = new ManualTimeoutScheduler();
    private final MutableClock clock = new MutableClock();
    private final Map<String, LogicDevice> devices = new LinkedHashMap<>();

    private AsmControlService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(stationDevice.getUniqueId()).thenReturn("logicdevice_station.th");
        devices.put("logicdevice_station.th", stationDevice);
        service = new AsmControlService(recordMapper, devices::get,
                executor, timeoutScheduler, clock, TIMEOUT);
    }

    private static AttrState<Object> state(String displayValue, UnitInfo unit) {
        return AttrState.<Object>builder()
                .deviceId("dev").attrId("temperature").value(0)
                .displayValue(displayValue).valueType(Object.class)
                .status(AttributeStatus.NORMAL)
                .context(com.ecat.core.Bus.event.EventContext.root(
                        com.ecat.core.Bus.event.EventContext.Source.DEVICE_POLL, "test"))
                .nativeUnit(unit)
                .lastUpdated(Instant.parse("2026-08-18T11:59:00Z"))
                .build();
    }

    private static WritableAttr okAttr(String attrId, String initialDisplay) {
        return new WritableAttr(attrId, true, state(initialDisplay, TemperatureUnit.CELSIUS),
                (v, self) -> {
                    self.setState(state(v, TemperatureUnit.CELSIUS));
                    return CompletableFuture.completedFuture(true);
                });
    }

    /** insert 桩：回填 id（模拟 useGeneratedKeys）。 */
    private void stubInsertWithId(final long id) {
        doAnswer(inv -> {
            AsmControlRecord r = inv.getArgument(0);
            r.setId(id);
            return 1;
        }).when(recordMapper).insert(any(AsmControlRecord.class));
    }

    // ===== 主链路：PENDING 先落 → 执行 → 回填 =====

    @Test
    void execute_landsPendingThenRefillsSuccessWithBeforeAfter() {
        stubInsertWithId(77L);
        WritableAttr attr = okAttr("temperature", "25.5");
        when(stationDevice.getAttrs()).thenReturn(attrs(attr));

        AsmControlRecord rec = service.execute(AsmControlOrigin.LOCAL, "consumer-x",
                "logicdevice_station.th", "temperature", "30.0");

        assertEquals(77L, rec.getId());
        assertEquals(AsmControlResult.PENDING, rec.getResult());
        assertEquals(AsmControlOrigin.LOCAL, rec.getOrigin());
        assertEquals("consumer-x", rec.getCaller());
        assertEquals("25.5 " + TemperatureUnit.CELSIUS, rec.getBeforeValue());
        assertEquals("30.0", rec.getRequestedValue());
        assertEquals("WRITE", rec.getAction());
        verify(recordMapper).insert(any(AsmControlRecord.class));
        verify(recordMapper, never()).updateResult(any(AsmControlRecord.class));

        clock.advance(Duration.ofMillis(300));
        executor.runAll();

        ArgumentCaptor<AsmControlRecord> cap = ArgumentCaptor.forClass(AsmControlRecord.class);
        verify(recordMapper).updateResult(cap.capture());
        AsmControlRecord done = cap.getValue();
        assertEquals(77L, done.getId());
        assertEquals(AsmControlResult.SUCCESS, done.getResult());
        assertEquals("30.0 " + TemperatureUnit.CELSIUS, done.getAfterValue());
        assertEquals(Long.valueOf(300L), done.getDurationMs());
        assertNull(done.getError());
        assertEquals(AsmControlResult.SUCCESS, rec.getResult());
    }

    @Test
    void execute_remoteOriginRecordedSameFunnel() {
        stubInsertWithId(1L);
        WritableAttr attr = okAttr("temperature", "25.5");
        when(stationDevice.getAttrs()).thenReturn(attrs(attr));

        AsmControlRecord rec = service.execute(AsmControlOrigin.REMOTE, "admin",
                "logicdevice_station.th", "temperature", "26.0");
        executor.runAll();

        assertEquals(AsmControlOrigin.REMOTE, rec.getOrigin());
        assertEquals("admin", rec.getCaller());
        assertEquals(AsmControlResult.SUCCESS, rec.getResult());
    }

    // ===== 失败路径 =====

    @Test
    void execute_futureExceptionRefillsFailedWithError() {
        stubInsertWithId(2L);
        WritableAttr attr = new WritableAttr("temperature", true, state("25.5", TemperatureUnit.CELSIUS),
                (v, self) -> {
                    CompletableFuture<Boolean> f = new CompletableFuture<>();
                    f.completeExceptionally(new IllegalStateException("device offline"));
                    return f;
                });
        when(stationDevice.getAttrs()).thenReturn(attrs(attr));

        AsmControlRecord rec = service.execute(AsmControlOrigin.LOCAL, "asm-alarm",
                "logicdevice_station.th", "temperature", "30.0");
        executor.runAll();

        assertEquals(AsmControlResult.FAILED, rec.getResult());
        assertTrue(rec.getError().contains("device offline"));
        verify(recordMapper).updateResult(any(AsmControlRecord.class));
    }

    @Test
    void execute_futureFalseRefillsFailed() {
        stubInsertWithId(3L);
        WritableAttr attr = new WritableAttr("temperature", true, state("25.5", TemperatureUnit.CELSIUS),
                (v, self) -> CompletableFuture.completedFuture(false));
        when(stationDevice.getAttrs()).thenReturn(attrs(attr));

        service.execute(AsmControlOrigin.LOCAL, "asm-alarm",
                "logicdevice_station.th", "temperature", "30.0");
        executor.runAll();

        ArgumentCaptor<AsmControlRecord> cap = ArgumentCaptor.forClass(AsmControlRecord.class);
        verify(recordMapper).updateResult(cap.capture());
        assertEquals(AsmControlResult.FAILED, cap.getValue().getResult());
    }

    @Test
    void execute_synchronousThrowRefillsFailed() {
        stubInsertWithId(4L);
        WritableAttr attr = new WritableAttr("temperature", true, state("25.5", TemperatureUnit.CELSIUS),
                (v, self) -> {
                    throw new IllegalArgumentException("bad command");
                });
        when(stationDevice.getAttrs()).thenReturn(attrs(attr));

        service.execute(AsmControlOrigin.LOCAL, "asm-alarm",
                "logicdevice_station.th", "temperature", "30.0");
        executor.runAll();

        ArgumentCaptor<AsmControlRecord> cap = ArgumentCaptor.forClass(AsmControlRecord.class);
        verify(recordMapper).updateResult(cap.capture());
        assertEquals(AsmControlResult.FAILED, cap.getValue().getResult());
        assertTrue(cap.getValue().getError().contains("bad command"));
    }

    // ===== 超时路径：如实记 TIMEOUT，迟到执行不双写、不猜结果 =====

    @Test
    void execute_timeoutRefillsTimeoutWithoutAfterAndLateRunDoesNotDoubleWrite() {
        stubInsertWithId(5L);
        WritableAttr attr = new WritableAttr("temperature", true, state("25.5", TemperatureUnit.CELSIUS),
                (v, self) -> new CompletableFuture<>()); // 永不完成
        when(stationDevice.getAttrs()).thenReturn(attrs(attr));

        AsmControlRecord rec = service.execute(AsmControlOrigin.LOCAL, "asm-alarm",
                "logicdevice_station.th", "temperature", "30.0");
        // 不跑 executor，直接触发超时
        clock.advance(TIMEOUT);
        timeoutScheduler.fireAll();

        assertEquals(AsmControlResult.TIMEOUT, rec.getResult());
        assertNull(rec.getAfterValue());
        assertEquals(Long.valueOf(TIMEOUT.toMillis()), rec.getDurationMs());
        verify(recordMapper, times(1)).updateResult(any(AsmControlRecord.class));

        // 迟到的执行完成不得再回填（不双写、不覆盖 TIMEOUT）
        executor.runAll();
        verify(recordMapper, times(1)).updateResult(any(AsmControlRecord.class));
        assertEquals(AsmControlResult.TIMEOUT, rec.getResult());
    }

    // ===== 严格模式校验：明确异常，不静默 =====

    @Test
    void execute_blankCallerRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(AsmControlOrigin.LOCAL, " ",
                "logicdevice_station.th", "temperature", "30.0"));
        verify(recordMapper, never()).insert(any(AsmControlRecord.class));
    }

    @Test
    void execute_nullOriginRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(null, "caller",
                "logicdevice_station.th", "temperature", "30.0"));
    }

    @Test
    void execute_nonStationUidRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(AsmControlOrigin.LOCAL, "caller",
                "logicdevice_adm.analyzer", "temperature", "30.0"));
    }

    @Test
    void execute_unknownDeviceRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(AsmControlOrigin.LOCAL, "caller",
                "logicdevice_station.nope", "temperature", "30.0"));
    }

    @Test
    void execute_unknownAttrRejected() {
        when(stationDevice.getAttrs()).thenReturn(attrs());
        assertThrows(IllegalArgumentException.class, () -> service.execute(AsmControlOrigin.LOCAL, "caller",
                "logicdevice_station.th", "temperature", "30.0"));
    }

    @Test
    void execute_nonWritableAttrRejected() {
        WritableAttr ro = okAttr("temperature", "25.5");
        WritableAttr readonly = new WritableAttr("temperature", false,
                state("25.5", TemperatureUnit.CELSIUS), (v, self) -> CompletableFuture.completedFuture(true));
        when(stationDevice.getAttrs()).thenReturn(attrs(readonly));
        assertThrows(IllegalStateException.class, () -> service.execute(AsmControlOrigin.LOCAL, "caller",
                "logicdevice_station.th", "temperature", "30.0"));
        verify(recordMapper, never()).insert(any(AsmControlRecord.class));
    }

    @Test
    void execute_blankValueRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(AsmControlOrigin.LOCAL, "caller",
                "logicdevice_station.th", "temperature", " "));
    }

    private static Map<String, com.ecat.core.State.AttributeBase<?>> attrs(
            com.ecat.core.State.AttributeBase<?>... list) {
        Map<String, com.ecat.core.State.AttributeBase<?>> m = new LinkedHashMap<>();
        for (com.ecat.core.State.AttributeBase<?> a : list) {
            m.put(a.getAttributeID(), a);
        }
        return m;
    }
}
