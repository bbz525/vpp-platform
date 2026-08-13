package io.vpp.platformapi.security;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import io.vpp.platformapi.common.ApiException;

@Component
public class ActorResolver {
    public ActorPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiException.forbidden();
        }
        if (authentication.getPrincipal() instanceof ActorPrincipal actor) {
            return actor;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (String value : jwt.getClaimAsStringList("roles")) {
                roles.add(Role.valueOf(value));
            }
            return new ActorPrincipal(tenantId, jwt.getSubject(), roles);
        }
        throw ApiException.forbidden();
    }

    public ActorPrincipal require(Role... anyRole) {
        ActorPrincipal actor = current();
        for (Role role : anyRole) {
            if (actor.has(role)) {
                return actor;
            }
        }
        throw ApiException.forbidden();
    }
}
