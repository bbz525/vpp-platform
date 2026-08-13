package io.vpp.platformapi.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.vpp.platformapi.audit.AuditDtos.AuditEventResponse;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEventResponse> list(UUID tenantId, String objectType, String objectId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, actor_id, action, object_type, object_id, reason, before_digest,
                       after_digest, result, occurred_at
                FROM audit_event WHERE tenant_id = ?
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (objectType != null && !objectType.isBlank()) {
            sql.append(" AND object_type = ?");
            args.add(objectType);
        }
        if (objectId != null && !objectId.isBlank()) {
            sql.append(" AND object_id = ?");
            args.add(objectId);
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT 500");
        return jdbc.query(sql.toString(), (result, row) -> new AuditEventResponse(
                result.getObject("id", UUID.class), result.getString("actor_id"),
                result.getString("action"), result.getString("object_type"),
                result.getString("object_id"), result.getString("reason"),
                result.getString("before_digest"), result.getString("after_digest"),
                result.getString("result"), result.getTimestamp("occurred_at").toInstant()),
                args.toArray());
    }
}
