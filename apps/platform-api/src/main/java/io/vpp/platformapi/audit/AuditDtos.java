package io.vpp.platformapi.audit;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class AuditDtos {
    private AuditDtos() {
    }

    public record AuditEventResponse(
            UUID id,
            @JsonProperty("actor_id") String actorId,
            String action,
            @JsonProperty("object_type") String objectType,
            @JsonProperty("object_id") String objectId,
            String reason,
            @JsonProperty("before_digest") String beforeDigest,
            @JsonProperty("after_digest") String afterDigest,
            String result,
            @JsonProperty("occurred_at") Instant occurredAt) {
    }
}
