package com.ecat.integration.EnvAirStationManagerIntegration.api;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AirStationSdkImpl;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSnapshotService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmUnitContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * SDK queryAlarmRecords：入参严格校验 + mapper 委派 + 不可变 DTO 映射。
 */
@ExtendWith(MockitoExtension.class)
class AsmAlarmSdkQueryTest {

    @Mock
    private AsmHistoryQueryMapper historyMapper;
    @Mock
    private AsmUnitContract unitContract;
    @Mock
    private AsmSnapshotService snapshotService;
    @Mock
    private AsmAlarmRecordMapper recordMapper;

    private AirStationSdkImpl sdk;

    private static final Instant START = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-19T00:00:00Z");

    @BeforeEach
    void setUp() {
        sdk = new AirStationSdkImpl(historyMapper, unitContract, snapshotService, recordMapper, org.mockito.Mockito.mock(com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService.class));
    }

    @Test
    void query_delegatesAndMapsToImmutableRows() {
        AsmAlarmRecord row = AsmAlarmRecord.builder()
                .id(1L).alarmType("3").ruleName("漏水")
                .logicDeviceUniqueId("logicdevice_station.security_alarm").attrId("water_leak")
                .severity("1").startTime(START).endTime(END).description("d").status("0")
                .resultContent("{\"value\":\"报警\"}").build();
        when(recordMapper.selectList("logicdevice_station.security_alarm", null, START, END, 500))
                .thenReturn(Collections.singletonList(row));

        List<SdkAlarmRecord> out = sdk.queryAlarmRecords("logicdevice_station.security_alarm", START, END, 500);
        assertEquals(1, out.size());
        SdkAlarmRecord r = out.get(0);
        assertEquals("3", r.getAlarmType());
        assertEquals("漏水", r.getRuleName());
        assertEquals("logicdevice_station.security_alarm", r.getLogicDeviceUniqueId());
        assertEquals("water_leak", r.getAttrId());
        assertEquals("1", r.getSeverity());
        assertEquals(START, r.getStartTime());
        assertEquals(END, r.getEndTime());
        assertEquals("0", r.getStatus());
    }

    @Test
    void query_nullDeviceAllowed_meansAllDevices() {
        when(recordMapper.selectList(isNull(String.class), isNull(String.class), any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());
        assertEquals(0, sdk.queryAlarmRecords(null, START, END, 10).size());
    }

    @Test
    void query_strictValidation() {
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmRecords(" ", START, END, 10)); // blank
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmRecords("u", null, END, 10));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmRecords("u", START, null, 10));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmRecords("u", END, START, 10));   // start>=end
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmRecords("u", START, END, 0));
        assertThrows(IllegalArgumentException.class, () -> sdk.queryAlarmRecords("u", START, END, 1001));
    }
}
