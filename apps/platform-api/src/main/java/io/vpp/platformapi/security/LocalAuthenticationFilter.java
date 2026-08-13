package io.vpp.platformapi.security;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class LocalAuthenticationFilter extends OncePerRequestFilter {
    private final ActorLookupService actors;

    public LocalAuthenticationFilter(ActorLookupService actors) {
        this.actors = actors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String tenantText = request.getHeader("X-VPP-Tenant-Id");
        String subject = request.getHeader("X-VPP-Subject");
        if (tenantText != null && subject != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UUID tenantId = UUID.fromString(tenantText);
                actors.findActive(tenantId, subject).ifPresent(actor -> {
                    var authorities = actor.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(actor, "N/A", authorities));
                });
            } catch (IllegalArgumentException ignored) {
                // Invalid local headers remain unauthenticated and are handled by the entry point.
            }
        }
        chain.doFilter(request, response);
    }
}
