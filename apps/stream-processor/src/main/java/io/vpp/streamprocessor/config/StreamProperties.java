package io.vpp.streamprocessor.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stream")
public record StreamProperties(boolean enabled, String environment, boolean redisTls, Catalog catalog,
        ClickHouse clickhouse, Kafka kafka, Quality quality, State state, Duration sinkTimeout) {
    private static final Set<String> UNSAFE = Set.of("changeme", "password", "example", "local-dev");

    public StreamProperties {
        requireText(environment, "stream.environment");
        if (sinkTimeout == null || sinkTimeout.isNegative() || sinkTimeout.isZero()) {
            throw new IllegalArgumentException("stream.sink-timeout must be positive");
        }
        if (enabled && "production".equalsIgnoreCase(environment)) {
            if (!"https".equalsIgnoreCase(URI.create(catalog.url()).getScheme())) {
                throw new IllegalArgumentException("stream catalog URL must use HTTPS in production");
            }
            String token = catalog.token().toLowerCase(Locale.ROOT);
            if (catalog.token().length() < 32 || UNSAFE.stream().anyMatch(token::contains)) {
                throw new IllegalArgumentException("stream catalog token is unsafe for production");
            }
            if ("PLAINTEXT".equalsIgnoreCase(kafka.securityProtocol())) {
                throw new IllegalArgumentException("stream Kafka transport must be encrypted in production");
            }
            if (!redisTls) {
                throw new IllegalArgumentException("stream Redis TLS is required in production");
            }
            if (!(clickhouse.url().contains("https://") || clickhouse.url().contains("ssl=true"))) {
                throw new IllegalArgumentException("stream ClickHouse transport must be encrypted in production");
            }
        }
    }

    public record Catalog(String url, String token, Duration refreshInterval,
            Duration missGracePeriod) {
        public Catalog {
            requireText(url, "stream.catalog.url");
            requireText(token, "stream.catalog.token");
            positive(refreshInterval, "stream.catalog.refresh-interval");
            positive(missGracePeriod, "stream.catalog.miss-grace-period");
        }
    }

    public record ClickHouse(String url, String username, String password) {
        public ClickHouse {
            requireText(url, "stream.clickhouse.url");
            requireText(username, "stream.clickhouse.username");
            requireText(password, "stream.clickhouse.password");
        }
    }

    public record Kafka(String groupId, String securityProtocol, String rawTopic, String normalizedTopic,
            String deviceStateTopic, String aggregateSnapshotTopic, String dlqTopic) {
        public Kafka {
            requireText(groupId, "stream.kafka.group-id");
            requireText(securityProtocol, "stream.kafka.security-protocol");
            requireText(rawTopic, "stream.kafka.raw-topic");
            requireText(normalizedTopic, "stream.kafka.normalized-topic");
            requireText(deviceStateTopic, "stream.kafka.device-state-topic");
            requireText(aggregateSnapshotTopic, "stream.kafka.aggregate-snapshot-topic");
            requireText(dlqTopic, "stream.kafka.dlq-topic");
        }
    }

    public record Quality(Duration maxClockDrift, Duration lateArrivalThreshold) {
        public Quality {
            positive(maxClockDrift, "stream.quality.max-clock-drift");
            positive(lateArrivalThreshold, "stream.quality.late-arrival-threshold");
        }
    }

    public record State(Duration dedupTtl, Duration deviceTtl, Duration aggregateTtl,
            Duration resumeTtl, long resumeMaxLength) {
        public State {
            positive(dedupTtl, "stream.state.dedup-ttl");
            positive(deviceTtl, "stream.state.device-ttl");
            positive(aggregateTtl, "stream.state.aggregate-ttl");
            positive(resumeTtl, "stream.state.resume-ttl");
            if (resumeMaxLength < 10 || resumeMaxLength > 100_000) {
                throw new IllegalArgumentException("stream.state.resume-max-length must be between 10 and 100000");
            }
        }
    }

    private static void positive(Duration value, String property) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " cannot be blank");
        }
    }
}
