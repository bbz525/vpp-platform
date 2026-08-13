package io.vpp.platformapi.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.outbox")
public record OutboxProperties(boolean enabled, Duration pollInterval, int batchSize,
        Duration sendTimeout) {
    public OutboxProperties {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("platform.outbox.poll-interval must be positive");
        }
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("platform.outbox.batch-size must be between 1 and 1000");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("platform.outbox.send-timeout must be positive");
        }
    }
}
