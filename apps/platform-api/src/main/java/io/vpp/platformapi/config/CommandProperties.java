package io.vpp.platformapi.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.commands")
public record CommandProperties(boolean dispatcherEnabled, boolean consumerEnabled,
        Duration pollInterval, Duration timeoutInterval, Duration ackTimeout,
        Duration stopTimeout, int batchSize) {
    public CommandProperties {
        requirePositive(pollInterval, "poll-interval");
        requirePositive(timeoutInterval, "timeout-interval");
        requirePositive(ackTimeout, "ack-timeout");
        requirePositive(stopTimeout, "stop-timeout");
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("platform.commands.batch-size must be between 1 and 1000");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("platform.commands." + name + " must be positive");
        }
    }
}
