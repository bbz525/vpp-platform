package io.vpp.streamprocessor.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import io.vpp.streamprocessor.catalog.DeviceCatalogEntry;
import io.vpp.streamprocessor.support.TestStreamProperties;

class TelemetryNormalizerTest {
    private static final UUID TENANT = UUID.fromString("7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941");
    private final ObjectMapper mapper = new ObjectMapper();
    private final TelemetryNormalizer normalizer = new TelemetryNormalizer(mapper,
            TestStreamProperties.create());
    private final DeviceCatalogEntry device = new DeviceCatalogEntry(TENANT, UUID.randomUUID(),
            "bess-001", UUID.randomUUID(), UUID.randomUUID(), "BATTERY", 1,
            mapper.createObjectNode(), "ACTIVE", 3);

    @Test
    void normalizesCanonicalMetricsAndPreservesSemanticIdentity() {
        UUID eventId = UUID.randomUUID();
        Instant time = Instant.parse("2026-08-12T01:00:00Z");
        RawTelemetry raw = new RawTelemetry(1, eventId, 42, time,
                Map.of("active_power_kw", -20.5, "soc_pct", 63.0), "RUNNING",
                Map.of("data_quality_source", "SIMULATED"));

        NormalizedResult first = normalizer.normalize(raw, device, time.plusSeconds(10),
                time.plusMillis(20));
        NormalizedResult replay = normalizer.normalize(raw, device, time.plusSeconds(10),
                time.plusMillis(20));

        assertThat(first.telemetry().eventId()).isEqualTo(eventId);
        assertThat(first.telemetry().aggregateId()).isEqualTo("bess-001");
        assertThat(first.telemetry().payload().deviceUuid()).isEqualTo(device.deviceId());
        assertThat(first.units()).containsEntry("active_power_kw", "kW")
                .containsEntry("soc_pct", "%");
        assertThat(first.telemetry().dataQuality().status()).isEqualTo("VALID");
        assertThat(first.telemetry().dataQuality().flags()).containsExactly("SIMULATED_SOURCE");
        assertThat(replay.telemetry().traceId()).isEqualTo(first.telemetry().traceId());
    }

    @Test
    void marksLateClockDriftSuspectWithoutDroppingHistory() {
        Instant deviceTime = Instant.parse("2026-08-12T01:00:00Z");
        RawTelemetry raw = new RawTelemetry(1, UUID.randomUUID(), 1, deviceTime,
                Map.of("soc_pct", 50.0), "IDLE", Map.of());

        NormalizedResult result = normalizer.normalize(raw, device,
                deviceTime.plusSeconds(601), deviceTime.plusSeconds(602));

        assertThat(result.telemetry().dataQuality().status()).isEqualTo("SUSPECT");
        assertThat(result.telemetry().dataQuality().flags())
                .containsExactly("CLOCK_DRIFT", "LATE_ARRIVAL");
    }

    @Test
    void rejectsUnknownAndOutOfRangeMetricsBeforeAnySink() {
        Instant time = Instant.parse("2026-08-12T01:00:00Z");
        RawTelemetry unknown = new RawTelemetry(1, UUID.randomUUID(), 1, time,
                Map.of("magic_voltage", 10.0), "IDLE", Map.of());
        RawTelemetry outOfRange = new RawTelemetry(1, UUID.randomUUID(), 2, time,
                Map.of("soc_pct", 101.0), "IDLE", Map.of());

        assertThatThrownBy(() -> normalizer.normalize(unknown, device, time, time))
                .isInstanceOfSatisfying(TelemetryValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("UNKNOWN_METRIC"));
        assertThatThrownBy(() -> normalizer.normalize(outOfRange, device, time, time))
                .isInstanceOfSatisfying(TelemetryValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("OUT_OF_RANGE"));
    }
}
