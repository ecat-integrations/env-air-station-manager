package com.ecat.integration.EnvAirStationManagerIntegration.support;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * PG timestamptz(带时区)列 ↔ {@link Instant} 映射 TypeHandler,经 OffsetDateTime 中转。
 *
 * <p><b>应用场景</b>:ASM 时间字段统一用 {@link Instant}(绝对时刻,UTC 时间线点)——PG JDBC 42.x+ 拒
 * timestamptz→java.time 隐式转换,默认 handler 直接抛;本 handler 经 OffsetDateTime 中转(ADM 同模式,
 * ASM 自建不 import ADM)。读方向兼容 OffsetDateTime/Timestamp/Instant 直通;未知类型严格模式抛
 * 不臆测转换。注册:per-mapper {@code typeHandler=} 局部注册(非全局,避免动 ruoyi core 的映射)。</p>
 *
 * @author coffee
 */
@MappedTypes(Instant.class)
public class TimestamptzInstantTypeHandler extends BaseTypeHandler<Instant> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
            throws SQLException {
        // Instant → OffsetDateTime(UTC) → timestamptz;Instant 本身 UTC,offset 恒为 UTC
        ps.setObject(i, OffsetDateTime.ofInstant(parameter, ZoneOffset.UTC));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toInstant(rs.getObject(columnName));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toInstant(rs.getObject(columnIndex));
    }

    @Override
    public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toInstant(cs.getObject(columnIndex));
    }

    /** 驱动原生时间对象统一转 {@link Instant};null 返 null;未知类型严格模式明确告知(不静默返 null 掩盖)。 */
    private static Instant toInstant(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Instant) {
            return (Instant) obj;
        }
        if (obj instanceof OffsetDateTime) {
            return ((OffsetDateTime) obj).toInstant();
        }
        if (obj instanceof Timestamp) {
            return ((Timestamp) obj).toInstant();
        }
        throw new IllegalStateException(
                "[诊断调试] TimestamptzInstantTypeHandler 遇未知时间类型:" + obj.getClass().getName());
    }
}
