package io.vpp.streamprocessor.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import io.vpp.streamprocessor.catalog.DeviceCatalogEntry;
import io.vpp.streamprocessor.normalization.NormalizedResult;
import io.vpp.streamprocessor.normalization.NormalizedTelemetry;

@Testcontainers(disabledWithoutDocker = true)
class ClickHouseTelemetryStoreIntegrationTest {
    @Container
    static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer(
            DockerImageName.parse("clickhouse/clickhouse-server:25.7.4.11-alpine"))
            .withDatabaseName("vpp");

    @Test
    void duplicatePhysicalWritesCollapseInBusinessReadViews() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        dataSource.setUrl(CLICKHOUSE.getJdbcUrl());
        dataSource.setUsername(CLICKHOUSE.getUsername());
        dataSource.setPassword(CLICKHOUSE.getPassword());
        ClickHouseTelemetryStore store = new ClickHouseTelemetryStore(dataSource);
        store.initializeSchema();

        NormalizedResult result = result();
        store.store(result, 42);
        store.store(result, 42);

        assertThat(count(dataSource, "SELECT count() FROM telemetry_metric")).isEqualTo(4);
        assertThat(count(dataSource, "SELECT count() FROM telemetry_metric_current")).isEqualTo(2);
        assertThat(count(dataSource, "SELECT count() FROM metric_15m")).isEqualTo(2);
    }

    private static long count(DriverManagerDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static NormalizedResult result() {
        UUID tenant = UUID.randomUUID();
        UUID portfolio = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Instant time = Instant.parse("2026-08-12T01:00:00Z");
        DeviceCatalogEntry catalog = new DeviceCatalogEntry(tenant, device, "bess-001", site,
                portfolio, "BATTERY", 1, new ObjectMapper().createObjectNode(), "ACTIVE", 3);
        NormalizedTelemetry telemetry = new NormalizedTelemetry("vpp.telemetry.normalized", 1,
                event, "TelemetryAccepted", tenant, "DEVICE", "bess-001", time,
                time.plusMillis(20), "0123456789abcdef0123456789abcdef", event.toString(), null,
                "telemetry-normalizer", new NormalizedTelemetry.DataQuality("VALID",
                        List.of("SIMULATED_SOURCE"), "SIMULATED"),
                new NormalizedTelemetry.Payload(portfolio, site, device, "BATTERY", 7,
                        time.plusMillis(10), Map.of("active_power_kw", 12.5, "soc_pct", 60.0)));
        return new NormalizedResult(telemetry, catalog,
                Map.of("active_power_kw", "kW", "soc_pct", "%"));
    }
}
