package io.vpp.platformapi.forecast;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import io.vpp.platformapi.config.ForecastProperties;
import io.vpp.platformapi.config.PlatformProperties;
import io.vpp.platformapi.config.RealtimeProperties;

@Component
public class ClickHouseForecastHistoryClient implements ForecastHistoryClient {
    private final JdbcTemplate jdbc;
    private final ForecastProperties properties;

    public ClickHouseForecastHistoryClient(RealtimeProperties realtime, ForecastProperties properties,
            PlatformProperties platform) {
        properties.validateFor(platform.environment());
        this.properties = properties;
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        dataSource.setUrl(realtime.clickhouse().url());
        dataSource.setUsername(realtime.clickhouse().username());
        dataSource.setPassword(realtime.clickhouse().password());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(Math.toIntExact(properties.readTimeout().toSeconds()));
        jdbc.setMaxRows(10_000);
    }

    @Override
    public List<Observation> query(UUID tenantId, String targetType, UUID targetId,
            String metric, Instant cutoff) {
        String targetColumn = "SITE".equals(targetType) ? "site_id" : "portfolio_id";
        String deviceType = "LOAD_KW".equals(metric) ? "METER" : "PV_INVERTER";
        String sql = """
                SELECT observed_at, sum(device_value) AS value,
                       if(countIf(device_valid = 0) > 0, 'SUSPECT', 'VALID') AS quality
                FROM (
                    SELECT device_id, toStartOfFifteenMinutes(device_time) AS observed_at,
                           avgIf(metric_value, quality_status = 'VALID') AS device_value,
                           if(countIf(quality_status != 'VALID') = 0, 1, 0) AS device_valid
                    FROM vpp.telemetry_metric_current
                    WHERE tenant_id = toUUID(?) AND %s = toUUID(?)
                      AND device_type = ? AND metric_name = 'active_power_kw'
                      AND device_time >= ? AND device_time < ?
                    GROUP BY device_id, observed_at
                    HAVING countIf(quality_status = 'VALID') > 0
                )
                GROUP BY observed_at
                ORDER BY observed_at
                LIMIT 10000
                """.formatted(targetColumn);
        Instant from = cutoff.minus(java.time.Duration.ofDays(properties.historyLookbackDays()));
        try {
            return jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setString(1, tenantId.toString());
                statement.setString(2, targetId.toString());
                statement.setString(3, deviceType);
                statement.setObject(4, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
                statement.setObject(5, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
                return statement;
            }, (result, row) -> new Observation(
                    result.getObject("observed_at", OffsetDateTime.class).toInstant(),
                    result.getDouble("value"), result.getString("quality")));
        } catch (DataAccessException exception) {
            throw new ForecastExecutionException("FORECAST_HISTORY_UNAVAILABLE",
                    "forecast history is temporarily unavailable", exception);
        }
    }
}
