package io.vpp.streamprocessor.sink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import io.vpp.streamprocessor.normalization.NormalizedResult;
import io.vpp.streamprocessor.normalization.NormalizedTelemetry;

@Component
public class ClickHouseTelemetryStore {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS telemetry_metric
            (
                tenant_id UUID,
                portfolio_id UUID,
                site_id UUID,
                device_id UUID,
                external_code String,
                device_type LowCardinality(String),
                event_id UUID,
                sequence UInt64,
                device_time DateTime64(3, 'UTC'),
                ingested_at DateTime64(3, 'UTC'),
                metric_name LowCardinality(String),
                metric_value Float64,
                unit LowCardinality(String),
                quality_status LowCardinality(String),
                quality_flags Array(LowCardinality(String)),
                source LowCardinality(String),
                event_version UInt64
            )
            ENGINE = ReplacingMergeTree(event_version)
            PARTITION BY toYYYYMM(device_time)
            ORDER BY (tenant_id, site_id, device_id, metric_name, device_time, event_id)
            TTL device_time + INTERVAL 365 DAY DELETE
            """;
    private static final String CREATE_CURRENT_VIEW = """
            CREATE VIEW IF NOT EXISTS telemetry_metric_current AS
            SELECT
                tenant_id,
                argMax(portfolio_id, event_version) AS portfolio_id,
                argMax(site_id, event_version) AS site_id,
                device_id,
                argMax(external_code, event_version) AS external_code,
                argMax(device_type, event_version) AS device_type,
                event_id,
                argMax(sequence, event_version) AS sequence,
                argMax(device_time, event_version) AS device_time,
                argMax(ingested_at, event_version) AS ingested_at,
                metric_name,
                argMax(metric_value, event_version) AS metric_value,
                argMax(unit, event_version) AS unit,
                argMax(quality_status, event_version) AS quality_status,
                argMax(quality_flags, event_version) AS quality_flags,
                argMax(source, event_version) AS source,
                max(event_version) AS latest_event_version
            FROM telemetry_metric
            GROUP BY tenant_id, device_id, event_id, metric_name
            """;
    private static final String CREATE_INTERVAL_VIEW = """
            CREATE VIEW IF NOT EXISTS metric_15m AS
            SELECT tenant_id, site_id, device_id, metric_name,
                   toStartOfFifteenMinutes(device_time) AS interval_start,
                   avg(metric_value) AS avg_value,
                   min(metric_value) AS min_value,
                   max(metric_value) AS max_value,
                   argMax(metric_value, device_time) AS last_value,
                   toUInt32(count()) AS sample_count,
                   toUInt32(countIf(quality_status = 'VALID')) AS valid_count,
                   max(ingested_at) AS calculated_at
            FROM telemetry_metric_current
            GROUP BY tenant_id, site_id, device_id, metric_name, interval_start
            """;
    private static final String INSERT = """
            INSERT INTO telemetry_metric
                (tenant_id, portfolio_id, site_id, device_id, external_code, device_type,
                 event_id, sequence, device_time, ingested_at, metric_name, metric_value,
                 unit, quality_status, quality_flags, source, event_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;

    public ClickHouseTelemetryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initializeSchema() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_CURRENT_VIEW);
            statement.execute(CREATE_INTERVAL_VIEW);
        } catch (SQLException exception) {
            throw new IllegalStateException("ClickHouse schema initialization failed", exception);
        }
    }

    public void store(NormalizedResult result, long eventVersion) {
        NormalizedTelemetry telemetry = result.telemetry();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(INSERT)) {
            for (var metric : telemetry.payload().metrics().entrySet()) {
                statement.setObject(1, telemetry.tenantId());
                statement.setObject(2, telemetry.payload().portfolioId());
                statement.setObject(3, telemetry.payload().siteId());
                statement.setObject(4, telemetry.payload().deviceUuid());
                statement.setString(5, telemetry.aggregateId());
                statement.setString(6, telemetry.payload().deviceType());
                statement.setObject(7, telemetry.eventId());
                statement.setLong(8, telemetry.payload().sequence());
                statement.setTimestamp(9, Timestamp.from(telemetry.occurredAt()));
                statement.setTimestamp(10, Timestamp.from(telemetry.payload().ingestedAt()));
                statement.setString(11, metric.getKey());
                statement.setDouble(12, metric.getValue());
                statement.setString(13, result.units().get(metric.getKey()));
                statement.setString(14, telemetry.dataQuality().status());
                statement.setArray(15, connection.createArrayOf("String",
                        telemetry.dataQuality().flags().toArray(String[]::new)));
                statement.setString(16, telemetry.dataQuality().source());
                statement.setLong(17, eventVersion);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new IllegalStateException("ClickHouse telemetry insert failed", exception);
        }
    }
}
