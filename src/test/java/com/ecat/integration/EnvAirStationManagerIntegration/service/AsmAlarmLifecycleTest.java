package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报警心跳窗生命周期 TDD：续期不落新行 / 窗口外新 episode / sweep 闭单摘槽 / POWER 恢复闭单 /
 * registry 启动重建。全 mock mapper（无 DB，sweep 侧见 AsmAlarmSweepSchedulerTest），
 * 时间全部显式 Instant（禁 sleep）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsmAlarmLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final String UID = "logicdevice_station.th";
    private static final String ATTR = "temperature";
    private static final String TYPE = "room_temp_abnormal";

    @Mock
    private AsmAlarmRecordMapper mapper;

    private AsmAlarmRegistry registry;
    private AsmAlarmLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        registry = new AsmAlarmRegistry();
        lifecycle = new AsmAlarmLifecycleService(mapper, registry);
        lenient().when(mapper.closeBatch(anyList(), any(Instant.class))).thenReturn(1);
    }

    private static AsmAlarmRecord hit(Instant now) {
        return AsmAlarmRecord.builder()
                .alarmType(TYPE).ruleName("t").logicDeviceUniqueId(UID)
                .attrId(ATTR).severity("0").startTime(now).endTime(null)
                .status("ACTIVE").lastBreachTime(now).description("d").build();
    }

    private static AsmAlarmRecord activeRow(long id, Instant start, Instant lastBreach) {
        return AsmAlarmRecord.builder().id(id).alarmType(TYPE).ruleName("t")
                .logicDeviceUniqueId(UID).attrId(ATTR).severity("0")
                .startTime(start).endTime(null).status("ACTIVE").lastBreachTime(lastBreach).build();
    }

    // ===== P1-2 心跳窗：续期 / 新 episode =====

    @Test
    void recordTrigger_activeExists_extendsWithoutNewRow() {
        when(mapper.selectActive(UID, ATTR, TYPE)).thenReturn(activeRow(7L, T0, T0));

        lifecycle.recordTrigger(hit(T0.plusSeconds(60)));

        verify(mapper).extendActive(7L, T0.plusSeconds(60));          // 只推 last_breach_time
        verify(mapper, never()).insert(any(AsmAlarmRecord.class));    // 不落新行
        assertEquals(1, registry.activeByUid(UID).size());            // registry 槽同步
        assertEquals(T0, registry.activeByUid(UID).get(0).getStartTime()); // start_time 不动
    }

    @Test
    void recordTrigger_noActive_insertsNewActiveEpisode() {
        when(mapper.selectActive(UID, ATTR, TYPE)).thenReturn(null);

        AsmAlarmRecord fired = hit(T0);
        lifecycle.recordTrigger(fired);

        verify(mapper).insert(fired);
        assertEquals("ACTIVE", fired.getStatus());
        assertEquals(T0, fired.getLastBreachTime());
        assertTrue(registry.hasActive(UID));
    }

    // ===== P1-6 POWER 恢复：落恢复行 + 闭 ACTIVE + 摘槽 =====

    @Test
    void recordTrigger_recovery_closesActiveRowAndSlot() {
        registry.put(new AsmAlarmRegistry.ActiveAlarm(UID, ATTR, TYPE, "t", T0, T0));
        when(mapper.selectActive(UID, ATTR, TYPE)).thenReturn(activeRow(7L, T0, T0));
        AsmAlarmRecord recovery = hit(T0.plusSeconds(30));
        recovery.setStatus("INACTIVE");
        recovery.setEndTime(T0.plusSeconds(30));
        recovery.setRecovery(true);

        lifecycle.recordTrigger(recovery);

        verify(mapper).insert(recovery);                                       // 恢复行照落
        verify(mapper).closeBatch(eq(Collections.singletonList(7L)), eq(T0.plusSeconds(30)));
        assertFalse(registry.hasActive(UID));                                  // 槽已摘
    }

    // ===== P1-3 registry 启动重建（重启孤儿徽章）=====

    @Test
    void rebuildFromActiveAlarms_restoresSlots() {
        AsmAlarmRecord otherAttr = activeRow(8L, T0.plusSeconds(1), T0.plusSeconds(60));
        otherAttr.setAttrId("humidity");
        registry.rebuildFromActive(Arrays.asList(activeRow(7L, T0, T0), otherAttr));

        List<AsmAlarmRegistry.ActiveAlarm> active = registry.activeByUid(UID);
        assertEquals(2, active.size());
        assertTrue(registry.hasActive(UID));
    }
}
