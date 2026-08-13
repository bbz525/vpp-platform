package io.vpp.devicesimulator.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HeartbeatPayload(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("event_id") UUID eventId,
        long sequence,
        @JsonProperty("device_time") Instant deviceTime,
        @JsonProperty("firmware_version") String firmwareVersion,
        @JsonProperty("uptime_seconds") long uptimeSeconds,
        Map<String, Object> extensions) {
}
