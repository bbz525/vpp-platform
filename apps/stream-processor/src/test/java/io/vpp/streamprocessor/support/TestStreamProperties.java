package io.vpp.streamprocessor.support;

import java.time.Duration;

import io.vpp.streamprocessor.config.StreamProperties;

public final class TestStreamProperties {
    private TestStreamProperties() {
    }

    public static StreamProperties create() {
        return new StreamProperties(true, "local", false,
                new StreamProperties.Catalog("http://127.0.0.1:8080",
                        "local-dev-platform-internal-token-only", Duration.ofSeconds(5),
                        Duration.ofSeconds(30)),
                new StreamProperties.ClickHouse("jdbc:clickhouse://127.0.0.1:8123/vpp",
                        "vpp", "local-dev-clickhouse-only"),
                new StreamProperties.Kafka("test-group", "PLAINTEXT", "raw", "normalized", "state",
                        "aggregate", "dlq"),
                new StreamProperties.Quality(Duration.ofMinutes(5), Duration.ofMinutes(2)),
                new StreamProperties.State(Duration.ofDays(7), Duration.ofHours(2),
                        Duration.ofMinutes(10), Duration.ofMinutes(10), 1000),
                Duration.ofSeconds(10));
    }
}
