package io.vpp.platformapi.audit;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.common.IdempotencyService;
import io.vpp.platformapi.security.ActorPrincipal;

@Component
public class AuditOutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final IdempotencyService digests;
    private final Clock clock = Clock.systemUTC();

    public AuditOutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, IdempotencyService digests) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.digests = digests;
    }

    public void succeeded(ActorPrincipal actor, String action, String objectType, UUID objectId,
            String reason, Object before, Object after) {
        write(actor.tenantId(), "USER", actor.subject(), action, objectType, objectId, reason, before, after);
    }

    public void systemSucceeded(UUID tenantId, String actorId, String action, String objectType,
            UUID objectId, String reason, Object before, Object after) {
        write(tenantId, "SYSTEM", actorId, action, objectType, objectId, reason, before, after);
    }

    private void write(UUID tenantId, String actorType, String actorId, String action, String objectType,
            UUID objectId, String reason, Object before, Object after) {
        UUID auditId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        String beforeDigest = before == null ? null : digests.digest(before);
        String afterDigest = after == null ? null : digests.digest(after);
        jdbc.update("""
                INSERT INTO audit_event
                    (id, tenant_id, actor_type, actor_id, action, object_type, object_id,
                     reason, before_digest, after_digest, result, metadata_json, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', '{}'::jsonb, ?)
                """, auditId, tenantId, actorType, actorId, action, objectType,
                objectId.toString(), reason, beforeDigest, afterDigest, Timestamp.from(occurredAt));
        Map<String, Object> event = Map.of(
                "schema", "vpp.audit.event",
                "schema_version", 1,
                "event_id", auditId,
                "tenant_id", tenantId,
                "actor_id", actorId,
                "action", action,
                "object_type", objectType,
                "object_id", objectId,
                "result", "SUCCEEDED",
                "occurred_at", occurredAt.toString());
        jdbc.update("""
                INSERT INTO outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, topic, message_key, payload_json)
                VALUES (?, ?, 'AUDIT_EVENT', ?, 'vpp.audit.events.v1', ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, auditId.toString(),
                tenantId + ":" + objectId, json(event));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("cannot serialize audit event", exception);
        }
    }
}
