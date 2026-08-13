package io.vpp.devicesimulator.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CommandRequest(
        String schema,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("command_id") UUID commandId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("device_id") String deviceId,
        String action,
        Map<String, Object> parameters,
        @JsonProperty("not_before") Instant notBefore,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("safety_config_version") long safetyConfigVersion,
        @JsonProperty("created_at") Instant createdAt) {
}
