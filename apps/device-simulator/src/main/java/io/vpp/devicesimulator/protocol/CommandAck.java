package io.vpp.devicesimulator.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CommandAck(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("command_id") UUID commandId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("device_time") Instant deviceTime,
        Status status,
        @JsonProperty("reason_code") String reasonCode,
        String message,
        Map<String, Object> actual) {

    public enum Status {
        ACCEPTED,
        EXECUTING,
        SUCCEEDED,
        FAILED
    }
}
