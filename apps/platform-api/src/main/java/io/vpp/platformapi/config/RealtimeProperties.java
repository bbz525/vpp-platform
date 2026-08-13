package io.vpp.platformapi.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.realtime")
public record RealtimeProperties(boolean enabled, Duration staleAfter, Duration pollInterval,
        Duration heartbeatInterval, int maxReplayEvents, int sendBufferBytes,
        Duration sendTimeLimit, List<String> allowedOrigins, ClickHouse clickhouse,
        boolean redisTls) {

    public RealtimeProperties {
        positive(staleAfter, "platform.realtime.stale-after");
        positive(pollInterval, "platform.realtime.poll-interval");
        positive(heartbeatInterval, "platform.realtime.heartbeat-interval");
        positive(sendTimeLimit, "platform.realtime.send-time-limit");
        if (maxReplayEvents < 1 || maxReplayEvents > 1_000) {
            throw new IllegalArgumentException("platform.realtime.max-replay-events must be between 1 and 1000");
        }
        if (sendBufferBytes < 8_192 || sendBufferBytes > 1_048_576) {
            throw new IllegalArgumentException("platform.realtime.send-buffer-bytes must be between 8192 and 1048576");
        }
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("platform.realtime.allowed-origins cannot be empty");
        }
    }

    public void validateFor(String environment) {
        if (!enabled || !"production".equalsIgnoreCase(environment)) return;
        if (!redisTls) {
            throw new IllegalArgumentException("platform realtime Redis TLS is required in production");
        }
        if (!(clickhouse.url().contains("https://") || clickhouse.url().contains("ssl=true"))) {
            throw new IllegalArgumentException("platform realtime ClickHouse transport must be encrypted in production");
        }
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*") || !origin.startsWith("https://"))) {
            throw new IllegalArgumentException("platform realtime production origins must be explicit HTTPS origins");
        }
    }

    public record ClickHouse(String url, String username, String password) {
        public ClickHouse {
            requireText(url, "platform.realtime.clickhouse.url");
            requireText(username, "platform.realtime.clickhouse.username");
            requireText(password, "platform.realtime.clickhouse.password");
        }
    }

    private static void positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " cannot be blank");
        }
    }
}
