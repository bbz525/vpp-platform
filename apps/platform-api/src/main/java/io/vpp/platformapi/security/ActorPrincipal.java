package io.vpp.platformapi.security;

import java.util.Set;
import java.util.UUID;

public record ActorPrincipal(UUID tenantId, String subject, Set<Role> roles) {
    public ActorPrincipal {
        roles = Set.copyOf(roles);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }
}
