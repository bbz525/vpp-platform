package io.vpp.platformapi.realtime;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import io.vpp.platformapi.config.PlatformProperties;
import io.vpp.platformapi.config.RealtimeProperties;
import io.vpp.platformapi.security.ActorLookupService;
import io.vpp.platformapi.security.ActorPrincipal;
import io.vpp.platformapi.security.Role;

@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {
    public static final String ACTOR_ATTRIBUTE = "vpp.actor";

    private final PlatformProperties platform;
    private final RealtimeProperties realtime;
    private final ActorLookupService actors;
    private final JwtDecoder jwtDecoder;
    private final RealtimeTicketService tickets;

    public RealtimeHandshakeInterceptor(PlatformProperties platform, RealtimeProperties realtime,
            ActorLookupService actors, JwtDecoder jwtDecoder, RealtimeTicketService tickets) {
        this.platform = platform;
        this.realtime = realtime;
        this.actors = actors;
        this.jwtDecoder = jwtDecoder;
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!realtime.enabled()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("ticket");
        ActorPrincipal actor = tickets.consume(ticket).orElseGet(() ->
                platform.auth().mode() == PlatformProperties.AuthMode.LOCAL
                        ? localActor(request) : jwtActor(request));
        if (actor == null || actor.roles().stream().noneMatch(RealtimeHandshakeInterceptor::canMonitor)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ACTOR_ATTRIBUTE, actor);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }

    private ActorPrincipal localActor(ServerHttpRequest request) {
        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        try {
            UUID tenant = UUID.fromString(query.getFirst("tenant_id"));
            String subject = query.getFirst("subject");
            if (subject == null || subject.isBlank()) return null;
            return actors.findActive(tenant, subject).orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ActorPrincipal jwtActor(ServerHttpRequest request) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("access_token");
        if (token == null || token.isBlank()) return null;
        try {
            Jwt jwt = jwtDecoder.decode(token);
            UUID tenant = UUID.fromString(jwt.getClaimAsString("tenant_id"));
            Set<Role> roles = EnumSet.noneOf(Role.class);
            var values = jwt.getClaimAsStringList("roles");
            if (values != null) values.forEach(value -> roles.add(Role.valueOf(value)));
            return new ActorPrincipal(tenant, jwt.getSubject(), roles);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean canMonitor(Role role) {
        return role == Role.TENANT_ADMIN || role == Role.OPERATOR
                || role == Role.AUDITOR || role == Role.SITE_ADMIN;
    }
}
