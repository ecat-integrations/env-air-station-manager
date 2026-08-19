package com.ecat.integration.EnvAirStationManagerIntegration.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TimestamptzInstantTypeHandler 单测——锁 timestamptz↔Instant 经 OffsetDateTime 中转的读写契约
 * （对齐 ADM 同名测法）。
 */
@ExtendWith(MockitoExtension.class)
class TimestamptzInstantTypeHandlerTest {

    private final TimestamptzInstantTypeHandler handler = new TimestamptzInstantTypeHandler();

    @Mock
    private PreparedStatement ps;
    @Mock
    private ResultSet rs;
    @Mock
    private CallableStatement cs;

    @Test
    void setNonNullParameter_writesOffsetDateTimeAtUtc() throws Exception {
        Instant instant = Instant.parse("2026-08-18T12:00:00Z");
        handler.setNonNullParameter(ps, 1, instant, null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ps).setObject(eq(1), captor.capture());
        OffsetDateTime written = (OffsetDateTime) captor.getValue();
        assertEquals(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC), written);
        assertEquals(ZoneOffset.UTC, written.getOffset());
    }

    @Test
    void getNullableResult_byName_fromOffsetDateTime() throws Exception {
        Instant instant = Instant.parse("2026-08-18T12:00:00Z");
        when(rs.getObject("data_time")).thenReturn(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        assertEquals(instant, handler.getNullableResult(rs, "data_time"));
    }

    @Test
    void getNullableResult_byIndex_fromOffsetDateTime() throws Exception {
        Instant instant = Instant.parse("2026-08-18T12:00:00Z");
        when(rs.getObject(2)).thenReturn(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        assertEquals(instant, handler.getNullableResult(rs, 2));
    }

    @Test
    void getNullableResult_fromCallableStatement() throws Exception {
        Instant instant = Instant.parse("2026-08-18T12:00:00Z");
        when(cs.getObject(3)).thenReturn(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        assertEquals(instant, handler.getNullableResult(cs, 3));
    }

    @Test
    void getNullableResult_fromTimestamp_andInstant() throws Exception {
        Instant instant = Instant.parse("2026-08-18T12:00:00Z");
        when(rs.getObject("col1")).thenReturn(Timestamp.from(instant));
        when(rs.getObject("col2")).thenReturn(instant);
        assertEquals(instant, handler.getNullableResult(rs, "col1"));
        assertEquals(instant, handler.getNullableResult(rs, "col2"));
    }

    @Test
    void getNullableResult_nullColumn() throws Exception {
        when(rs.getObject("col")).thenReturn(null);
        assertNull(handler.getNullableResult(rs, "col"));
    }

    @Test
    void getNullableResult_unknownTypeThrows() throws Exception {
        when(rs.getObject("col")).thenReturn("2026-08-18");
        assertThrows(IllegalStateException.class, () -> handler.getNullableResult(rs, "col"));
    }
}
