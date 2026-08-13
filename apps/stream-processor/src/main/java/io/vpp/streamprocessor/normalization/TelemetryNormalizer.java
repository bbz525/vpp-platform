package io.vpp.streamprocessor.normalization;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.vpp.streamprocessor.catalog.DeviceCatalogEntry;
import io.vpp.streamprocessor.config.StreamProperties;

@Component
public class TelemetryNormalizer {
    private static final Map<String, Map<String, MetricSpec>> CANONICAL = Map.of(
            "METER", specs(
                    spec("active_power_kw", "kW", null, null),
                    spec("energy_import_kwh", "kWh", 0.0, null)),
            "PV_INVERTER", specs(
                    spec("active_power_kw", "kW", 0.0, null),
                    spec("energy_generated_kwh", "kWh", 0.0, null),
                    spec("irradiance_w_m2", "W/m2", 0.0, 2000.0)),
            "BATTERY", specs(
                    spec("active_power_kw", "kW", null, null),
                    spec("soc_pct", "%", 0.0, 100.0),
                    spec("available_capacity_kwh", "kWh", 0.0, null)),
            "EV_CHARGER", specs(
                    spec("active_power_kw", "kW", 0.0, null),
                    spec("session_energy_kwh", "kWh", 0.0, null)));

    private final ObjectMapper mapper;
    private final StreamProperties properties;

    public TelemetryNormalizer(ObjectMapper mapper, StreamProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public RawTelemetry parse(byte[] payload) {
        try {
            return mapper.readValue(payload, RawTelemetry.class);
        } catch (JacksonException exception) {
            throw new TelemetryValidationException("MALFORMED_JSON", "raw telemetry is not valid JSON");
        }
    }

    public NormalizedResult normalize(RawTelemetry raw, DeviceCatalogEntry catalog,
            Instant receivedAt, Instant producedAt) {
        if (raw.schemaVersion() != 1) {
            throw invalid("SCHEMA_MISMATCH", "unsupported telemetry schema version");
        }
        if (raw.eventId() == null || raw.deviceTime() == null || raw.metrics() == null
                || raw.metrics().isEmpty()) {
            throw invalid("REQUIRED_FIELD_MISSING", "telemetry required field is missing");
        }
        if (raw.sequence() < 0) {
            throw invalid("INVALID_SEQUENCE", "sequence must be non-negative");
        }

        Map<String, MetricSpec> allowed = new LinkedHashMap<>(CANONICAL.getOrDefault(
                catalog.deviceType(), Map.of()));
        applyPointSchemaOverrides(allowed, catalog.pointSchema());
        Map<String, String> units = new LinkedHashMap<>();
        for (Map.Entry<String, Double> metric : raw.metrics().entrySet()) {
            MetricSpec definition = allowed.get(metric.getKey());
            if (definition == null) {
                throw invalid("UNKNOWN_METRIC", "metric is not defined by the device model: "
                        + metric.getKey());
            }
            Double value = metric.getValue();
            if (value == null || !Double.isFinite(value)) {
                throw invalid("NON_FINITE_METRIC", "metric must be finite: " + metric.getKey());
            }
            if ((definition.minimum() != null && value < definition.minimum())
                    || (definition.maximum() != null && value > definition.maximum())) {
                throw invalid("OUT_OF_RANGE", "metric is outside physical bounds: " + metric.getKey());
            }
            units.put(metric.getKey(), definition.unit());
        }

        List<String> flags = new ArrayList<>();
        Duration signedAge = Duration.between(raw.deviceTime(), receivedAt);
        if (signedAge.abs().compareTo(properties.quality().maxClockDrift()) > 0) {
            flags.add("CLOCK_DRIFT");
        }
        if (!signedAge.isNegative()
                && signedAge.compareTo(properties.quality().lateArrivalThreshold()) > 0) {
            flags.add("LATE_ARRIVAL");
        }
        String source = extension(raw.extensions(), "data_quality_source", "UNKNOWN");
        if ("SIMULATED".equals(source)) flags.add("SIMULATED_SOURCE");
        String quality = flags.stream().anyMatch(flag -> !"SIMULATED_SOURCE".equals(flag))
                ? "SUSPECT" : "VALID";

        NormalizedTelemetry telemetry = new NormalizedTelemetry(
                "vpp.telemetry.normalized", 1, raw.eventId(), "TelemetryAccepted",
                catalog.tenantId(), "DEVICE", catalog.externalCode(), raw.deviceTime(),
                producedAt, UUID.nameUUIDFromBytes(raw.eventId().toString()
                        .getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""),
                raw.eventId().toString(), null, "telemetry-normalizer",
                new NormalizedTelemetry.DataQuality(quality, List.copyOf(flags), source),
                new NormalizedTelemetry.Payload(catalog.portfolioId(), catalog.siteId(),
                        catalog.deviceId(), catalog.deviceType(), raw.sequence(), receivedAt,
                        Map.copyOf(raw.metrics())));
        return new NormalizedResult(telemetry, catalog, Map.copyOf(units));
    }

    private static void applyPointSchemaOverrides(Map<String, MetricSpec> allowed, JsonNode schema) {
        if (schema == null || !schema.isObject()) return;
        schema.properties().forEach(entry -> {
            JsonNode definition = entry.getValue();
            if (!definition.isObject() || definition.get("unit") == null) return;
            String name = entry.getKey();
            String unit = definition.get("unit").asString();
            Double minimum = numberOrNull(definition.get("minimum"));
            Double maximum = numberOrNull(definition.get("maximum"));
            allowed.put(name, new MetricSpec(name, unit, minimum, maximum));
        });
    }

    private static Double numberOrNull(JsonNode value) {
        return value == null || !value.isNumber() ? null : value.asDouble();
    }

    private static String extension(Map<String, Object> extensions, String key, String fallback) {
        if (extensions == null || extensions.get(key) == null) return fallback;
        return extensions.get(key).toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static TelemetryValidationException invalid(String code, String message) {
        return new TelemetryValidationException(code, message);
    }

    private static MetricSpec spec(String name, String unit, Double minimum, Double maximum) {
        return new MetricSpec(name, unit, minimum, maximum);
    }

    private static Map<String, MetricSpec> specs(MetricSpec... values) {
        Map<String, MetricSpec> result = new LinkedHashMap<>();
        for (MetricSpec value : values) result.put(value.name(), value);
        return Map.copyOf(result);
    }

    private record MetricSpec(String name, String unit, Double minimum, Double maximum) {
    }
}
