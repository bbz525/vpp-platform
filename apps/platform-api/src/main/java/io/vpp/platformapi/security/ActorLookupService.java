package io.vpp.platformapi.security;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

@Service
public class ActorLookupService {
    private final JdbcTemplate jdbc;

    public ActorLookupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActorPrincipal> findActive(UUID tenantId, String subject) {
        var users = jdbc.query("""
                SELECT id FROM app_user WHERE tenant_id = ? AND external_subject = ? AND status = 'ACTIVE'
                """, (result, row) -> result.getObject("id", UUID.class), tenantId, subject);
        if (users.isEmpty()) {
            return Optional.empty();
        }
        UUID userId = users.getFirst();
        EnumSet<Role> roles = EnumSet.noneOf(Role.class);
        jdbc.query("SELECT role FROM app_user_role WHERE tenant_id = ? AND user_id = ?",
                (RowCallbackHandler) result -> roles.add(Role.valueOf(result.getString("role"))),
                tenantId, userId);
        return Optional.of(new ActorPrincipal(tenantId, subject, roles));
    }
}
