package io.vpp.streamprocessor.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import io.vpp.streamprocessor.normalization.NormalizedTelemetry;
import io.vpp.streamprocessor.support.TestStreamProperties;

@Testcontainers(disabledWithoutDocker = true)
class RedisStateProjectorIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.1-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisStateProjector projector;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        projector = new RedisStateProjector(redis, new ObjectMapper(), TestStreamProperties.create());
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void olderEventsCannotRegressStateAndAggregateUsesPowerDelta() {
        UUID tenant = UUID.randomUUID();
        UUID portfolio = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Instant base = Instant.parse("2026-08-12T01:00:00Z");
        NormalizedTelemetry first = telemetry(tenant, portfolio, site, device, UUID.randomUUID(),
                base, 10, 10.0);
        NormalizedTelemetry older = telemetry(tenant, portfolio, site, device, UUID.randomUUID(),
                base.minusSeconds(5), 9, 99.0);
        NormalizedTelemetry newer = telemetry(tenant, portfolio, site, device, UUID.randomUUID(),
                base.plusSeconds(5), 11, 20.0);

        assertThat(projector.project(first, "ACTIVE")).isEqualTo(RedisProjectionResult.UPDATED);
        assertThat(projector.project(first, "ACTIVE"))
                .isEqualTo(RedisProjectionResult.ALREADY_PROJECTED);
        assertThat(projector.project(older, "ACTIVE"))
                .isEqualTo(RedisProjectionResult.OLDER_IGNORED);
        assertThat(projector.project(newer, "ACTIVE")).isEqualTo(RedisProjectionResult.UPDATED);

        String prefix = "vpp:{" + tenant + "}:";
        assertThat(redis.opsForHash().get(prefix + "device:" + device + ":state", "event_id"))
                .isEqualTo(newer.eventId().toString());
        assertThat(redis.opsForHash().get(prefix + "device:" + device + ":state", "active_power_kw"))
                .isEqualTo("20");
        assertThat(redis.opsForHash().get(prefix + "site:" + site + ":snapshot", "active_power_kw"))
                .isEqualTo("20");
        assertThat(redis.opsForHash().get(prefix + "portfolio:" + portfolio + ":snapshot",
                "active_power_kw")).isEqualTo("20");
        var resumeEvents = redis.opsForStream().range(
                prefix + "ws:portfolio:" + portfolio + ":snapshot", Range.unbounded());
        assertThat(resumeEvents).hasSize(2);
        assertThat(resumeEvents.get(0).getValue()).containsEntry("cursor", "0000000000000001")
                .containsEntry("occurred_at", first.occurredAt().toString());
        assertThat(String.valueOf(resumeEvents.get(1).getValue().get("data")))
                .contains("\"active_power_kw\":20.0").contains("\"quality\":\"VALID\"");

        assertThat(projector.wasProcessed(newer)).isFalse();
        projector.markProcessed(newer);
        assertThat(projector.wasProcessed(newer)).isTrue();
    }

    private static NormalizedTelemetry telemetry(UUID tenant, UUID portfolio, UUID site,
            UUID device, UUID event, Instant occurredAt, long sequence, double power) {
        return new NormalizedTelemetry("vpp.telemetry.normalized", 1, event,
                "TelemetryAccepted", tenant, "DEVICE", "bess-001", occurredAt,
                occurredAt.plusMillis(20), "0123456789abcdef0123456789abcdef", event.toString(),
                null, "telemetry-normalizer", new NormalizedTelemetry.DataQuality("VALID",
                        List.of(), "MEASURED"), new NormalizedTelemetry.Payload(portfolio, site,
                        device, "BATTERY", sequence, occurredAt.plusMillis(10),
                        Map.of("active_power_kw", power, "soc_pct", 60.0)));
    }
}
