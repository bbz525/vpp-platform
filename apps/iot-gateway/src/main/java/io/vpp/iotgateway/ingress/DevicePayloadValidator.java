package io.vpp.iotgateway.ingress;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.vpp.iotgateway.config.GatewayProperties;
import io.vpp.iotgateway.identity.DeviceIdentity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DevicePayloadValidator {
    private static final Pattern METRIC_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern DEVICE_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");
    private static final Set<String> SECRET_KEYS = Set.of(
            "credential", "password", "access_token", "refresh_token", "client_secret",
            "private_key");
    private static final Set<String> TELEMETRY_FIELDS = Set.of(
            "schema_version", "event_id", "sequence", "device_time", "metrics", "status",
            "extensions");
    private static final Set<String> HEARTBEAT_FIELDS = Set.of(
            "schema_version", "event_id", "sequence", "device_time", "firmware_version",
            "uptime_seconds", "extensions");
    private static final Map<String, Set<String>> LOCAL_POINT_TABLES = Map.of(
            "meter-", Set.of("active_power_kw", "energy_import_kwh", "voltage_v",
                    "current_a", "frequency_hz"),
            "pv-", Set.of("active_power_kw", "energy_generated_kwh", "irradiance_w_m2",
                    "temperature_c"),
            "bess-", Set.of("active_power_kw", "soc_pct", "available_capacity_kwh",
                    "soh_pct", "cell_temperature_c"),
            "evse-", Set.of("active_power_kw", "session_energy_kwh", "vehicle_soc_pct"));

    private final ObjectMapper objectMapper;
    private final int maxPayloadBytes;

    public DevicePayloadValidator(ObjectMapper objectMapper, GatewayProperties properties) {
        this.objectMapper = objectMapper;
        this.maxPayloadBytes = properties.ingress().maxPayloadBytes();
    }

    public void validateTelemetry(DeviceIdentity identity, byte[] payload) {
        JsonNode root = readObject(payload);
        rejectUnknownFields(root, TELEMETRY_FIELDS);
        validateCommonUpload(root);
        JsonNode metrics = required(root, "metrics");
        if (!metrics.isObject() || metrics.isEmpty() || metrics.size() > 128) {
            throw invalid("INVALID_METRICS");
        }
        Set<String> pointTable = pointTable(identity.deviceId());
        for (Map.Entry<String, JsonNode> metric : metrics.properties()) {
            if (!METRIC_NAME.matcher(metric.getKey()).matches()
                    || !metric.getValue().isNumber()
                    || !Double.isFinite(metric.getValue().doubleValue())) {
                throw invalid("INVALID_METRIC_VALUE");
            }
            if (!pointTable.contains(metric.getKey())) {
                throw invalid("UNREGISTERED_METRIC");
            }
        }
        assertNoSecrets(root);
    }

    public void validateHeartbeat(byte[] payload) {
        JsonNode root = readObject(payload);
        rejectUnknownFields(root, HEARTBEAT_FIELDS);
        validateCommonUpload(root);
        JsonNode uptime = root.get("uptime_seconds");
        if (uptime != null && (!uptime.isIntegralNumber() || uptime.longValue() < 0)) {
            throw invalid("INVALID_UPTIME");
        }
        assertNoSecrets(root);
    }

    public void validateCommandAck(byte[] payload) {
        JsonNode root = readObject(payload);
        validateVersion(root);
        uuid(root, "event_id");
        uuid(root, "command_id");
        text(root, "idempotency_key", 8, 256);
        instant(root, "device_time");
        String status = text(root, "status", 1, 32);
        if (!Set.of("ACCEPTED", "EXECUTING", "SUCCEEDED", "FAILED").contains(status)) {
            throw invalid("INVALID_COMMAND_STATUS");
        }
        assertNoSecrets(root);
    }

    public ValidatedCommand validateCommandRequest(byte[] payload) {
        JsonNode root = readObject(payload);
        if (!"vpp.command.requested".equals(text(root, "schema", 1, 128))) {
            throw invalid("INVALID_COMMAND_SCHEMA");
        }
        validateVersion(root);
        String commandId = uuid(root, "command_id").toString();
        String idempotencyKey = text(root, "idempotency_key", 8, 256);
        UUID tenantId = uuid(root, "tenant_id");
        String deviceId = text(root, "device_id", 1, 128);
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw invalid("INVALID_DEVICE_ID");
        }
        String action = text(root, "action", 1, 64);
        if (!Set.of("SET_POWER", "STOP", "SET_ACTIVE_POWER_LIMIT", "SET_CHARGE_LIMIT",
                "PAUSE", "RESUME").contains(action)) {
            throw invalid("INVALID_COMMAND_ACTION");
        }
        if (!required(root, "parameters").isObject()) {
            throw invalid("INVALID_COMMAND_PARAMETERS");
        }
        Instant notBefore = instant(root, "not_before");
        Instant expiresAt = instant(root, "expires_at");
        if (!notBefore.isBefore(expiresAt)) {
            throw invalid("INVALID_COMMAND_WINDOW");
        }
        assertNoSecrets(root);
        return new ValidatedCommand(new DeviceIdentity(tenantId, deviceId), commandId,
                idempotencyKey, notBefore, expiresAt);
    }

    private JsonNode readObject(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > maxPayloadBytes) {
            throw invalid("PAYLOAD_SIZE_INVALID");
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw invalid("PAYLOAD_NOT_OBJECT");
            }
            return root;
        } catch (PayloadValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PayloadValidationException("MALFORMED_JSON", exception);
        }
    }

    private static void validateCommonUpload(JsonNode root) {
        validateVersion(root);
        uuid(root, "event_id");
        JsonNode sequence = required(root, "sequence");
        if (!sequence.isIntegralNumber() || sequence.longValue() < 0) {
            throw invalid("INVALID_SEQUENCE");
        }
        instant(root, "device_time");
    }

    private static void validateVersion(JsonNode root) {
        JsonNode version = required(root, "schema_version");
        if (!version.isIntegralNumber() || version.intValue() != 1) {
            throw invalid("UNSUPPORTED_SCHEMA_VERSION");
        }
    }

    private static UUID uuid(JsonNode root, String field) {
        try {
            return UUID.fromString(text(root, field, 36, 36));
        } catch (IllegalArgumentException exception) {
            throw new PayloadValidationException("INVALID_" + field.toUpperCase(), exception);
        }
    }

    private static Instant instant(JsonNode root, String field) {
        try {
            return Instant.parse(text(root, field, 1, 64));
        } catch (DateTimeParseException exception) {
            throw new PayloadValidationException("INVALID_" + field.toUpperCase(), exception);
        }
    }

    private static String text(JsonNode root, String field, int min, int max) {
        JsonNode value = required(root, field);
        if (!value.isString()) {
            throw invalid("INVALID_" + field.toUpperCase());
        }
        String text = value.asString();
        if (text.length() < min || text.length() > max) {
            throw invalid("INVALID_" + field.toUpperCase());
        }
        return text;
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw invalid("MISSING_" + field.toUpperCase());
        }
        return value;
    }

    private static void rejectUnknownFields(JsonNode root, Set<String> allowed) {
        if (!allowed.containsAll(root.propertyNames())) {
            throw invalid("UNKNOWN_PAYLOAD_FIELD");
        }
    }

    private static Set<String> pointTable(String deviceId) {
        return LOCAL_POINT_TABLES.entrySet().stream()
                .filter(entry -> deviceId.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> invalid("UNKNOWN_DEVICE_POINT_TABLE"));
    }

    private static void assertNoSecrets(JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (SECRET_KEYS.contains(property.getKey().toLowerCase())) {
                    throw invalid("SECRET_FIELD_FORBIDDEN");
                }
                assertNoSecrets(property.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(DevicePayloadValidator::assertNoSecrets);
        }
    }

    private static PayloadValidationException invalid(String reason) {
        return new PayloadValidationException(reason);
    }
}
