package io.vpp.platformapi.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<UUID> replay(UUID tenantId, String operation, String key, Object request) {
        String digest = digest(request);
        var rows = jdbc.query("""
                SELECT request_digest, object_id FROM api_idempotency
                WHERE tenant_id = ? AND operation = ? AND idempotency_key = ?
                """, (result, row) -> new Stored(result.getString("request_digest"),
                        result.getObject("object_id", UUID.class)), tenantId, operation, key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Stored stored = rows.getFirst();
        if (!MessageDigest.isEqual(stored.digest().getBytes(StandardCharsets.US_ASCII),
                digest.getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.conflict("idempotency key was already used with another request");
        }
        return Optional.of(stored.objectId());
    }

    public void remember(UUID tenantId, String operation, String key, Object request, UUID objectId) {
        jdbc.update("""
                INSERT INTO api_idempotency
                    (tenant_id, operation, idempotency_key, request_digest, object_id)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, operation, key, digest(request), objectId);
    }

    public String digest(Object value) {
        try {
            byte[] json = mapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("cannot serialize idempotency request", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Stored(String digest, UUID objectId) {
    }
}
