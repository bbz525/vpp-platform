package io.vpp.platformapi.realtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.config.PlatformProperties;
import io.vpp.platformapi.config.RealtimeProperties;
import io.vpp.platformapi.realtime.RealtimeDtos.TelemetryPoint;
import io.vpp.platformapi.realtime.RealtimeDtos.TelemetrySeries;

@Component
public class ClickHouseQueryClient {
    private final JdbcTemplate jdbc;

    public ClickHouseQueryClient(RealtimeProperties properties, PlatformProperties platform) {
        properties.validateFor(platform.environment());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        dataSource.setUrl(properties.clickhouse().url());
        dataSource.setUsername(properties.clickhouse().username());
        dataSource.setPassword(properties.clickhouse().password());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        jdbc.setMaxRows(10_000);
    }

    public List<TelemetrySeries> query(UUID tenantId, String targetColumn, UUID targetId,
            String metric, Instant from, Instant to, int bucket) {
        String sql = """
                SELECT external_code, device_type,
                       toStartOfInterval(device_time, INTERVAL %d SECOND) AS observed_at,
                       avg(metric_value) AS value, any(unit) AS unit,
                       if(countIf(quality_status != 'VALID') > 0, 'SUSPECT', 'VALID') AS quality
                FROM vpp.telemetry_metric_current
                WHERE tenant_id = toUUID(?) AND %s = toUUID(?) AND metric_name = ?
                  AND device_time >= ? AND device_time < ?
                GROUP BY external_code, device_type, observed_at
                ORDER BY external_code, observed_at
                LIMIT 10000
                """.formatted(bucket, targetColumn);
        Map<String, MutableSeries> series = new LinkedHashMap<>();
        try {
            jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setString(1, tenantId.toString());
                statement.setString(2, targetId.toString());
                statement.setString(3, metric);
                statement.setObject(4, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
                statement.setObject(5, OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
                return statement;
            }, (RowCallbackHandler) result -> collect(result, series));
        } catch (DataAccessException exception) {
            throw ApiException.unavailable("telemetry history is temporarily unavailable");
        }
        return series.values().stream().map(value -> new TelemetrySeries(value.externalCode(),
                value.deviceType(), value.unit(), List.copyOf(value.points()))).toList();
    }

    private static void collect(ResultSet result, Map<String, MutableSeries> series) throws SQLException {
        String code = result.getString("external_code");
        String type = result.getString("device_type");
        String unit = result.getString("unit");
        MutableSeries value = series.computeIfAbsent(code,
                ignored -> new MutableSeries(code, type, unit, new ArrayList<>()));
        value.points().add(new TelemetryPoint(result.getObject("observed_at", OffsetDateTime.class).toInstant(),
                result.getDouble("value"), result.getString("quality")));
    }

    private record MutableSeries(String externalCode, String deviceType, String unit,
            List<TelemetryPoint> points) {
    }
}
