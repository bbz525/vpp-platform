package io.vpp.platformapi.realtime;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.security.ActorPrincipal;
import io.vpp.platformapi.security.Role;

@Service
public class RealtimeTicketService {
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RealtimeTicketService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Ticket issue(ActorPrincipal actor) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try {
            redis.opsForValue().set(key(value), mapper.writeValueAsString(new TicketActor(
                    actor.tenantId(), actor.subject(), actor.roles())), TTL);
        } catch (JacksonException exception) {
            throw new IllegalStateException("cannot issue realtime ticket", exception);
        }
        return new Ticket(value, Instant.now().plus(TTL));
    }

    public Optional<ActorPrincipal> consume(String ticket) {
        if (ticket == null || !ticket.matches("^[A-Za-z0-9_-]{43}$")) return Optional.empty();
        String json = redis.opsForValue().getAndDelete(key(ticket));
        if (json == null) return Optional.empty();
        try {
            TicketActor value = mapper.readValue(json, TicketActor.class);
            return Optional.of(new ActorPrincipal(value.tenantId(), value.subject(),
                    EnumSet.copyOf(value.roles())));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String key(String ticket) {
        return "vpp:ws:ticket:" + ticket;
    }

    public record Ticket(String ticket, Instant expiresAt) {
    }

    private record TicketActor(UUID tenantId, String subject, Set<Role> roles) {
    }
}
