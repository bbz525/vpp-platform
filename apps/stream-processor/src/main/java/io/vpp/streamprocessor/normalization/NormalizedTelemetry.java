package io.vpp.streamprocessor.normalization;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NormalizedTelemetry(
        String schema,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("aggregate_type") String aggregateType,
        @JsonProperty("aggregate_id") String aggregateId,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("produced_at") Instant producedAt,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("causation_id") String causationId,
        String producer,
        @JsonProperty("data_quality") DataQuality dataQuality,
        Payload payload) {

    public record DataQuality(String status, List<String> flags, String source) {
    }

    public record Payload(
            @JsonProperty("portfolio_id") UUID portfolioId,
            @JsonProperty("site_id") UUID siteId,
            @JsonProperty("device_uuid") UUID deviceUuid,
            @JsonProperty("device_type") String deviceType,
            long sequence,
            @JsonProperty("ingested_at") Instant ingestedAt,
            Map<String, Double> metrics) {
    }
}
