package io.vpp.platformapi.realtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RealtimeEventStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RealtimeEventStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public EventWindow window(UUID tenantId, UUID portfolioId) {
        return window(tenantId, "ws:portfolio:" + portfolioId + ":snapshot", "ws:portfolio:" + portfolioId + ":cursor");
    }

    public EventWindow alarmWindow(UUID tenantId) {
        return window(tenantId, "ws:alarms", "ws:alarms:cursor");
    }

    private EventWindow window(UUID tenantId, String stream, String cursorKey) {
        String prefix = "vpp:{" + tenantId + "}:";
        try {
            var records = redis.opsForStream().range(
                    prefix + stream, Range.unbounded());
            List<RealtimeEvent> events = new ArrayList<>();
            for (var record : records) {
                Map<Object, Object> fields = record.getValue();
                String cursor = String.valueOf(fields.get("cursor"));
                String occurredAt = String.valueOf(fields.get("occurred_at"));
                String data = String.valueOf(fields.get("data"));
                events.add(new RealtimeEvent(cursor, Instant.parse(occurredAt), mapper.readTree(data)));
            }
            String cursor = redis.opsForValue().get(prefix + cursorKey);
            long globalCursor = cursor == null ? 0 : Long.parseLong(cursor);
            return new EventWindow(List.copyOf(events), globalCursor);
        } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("realtime resume store is unavailable", exception);
        }
    }

    public record RealtimeEvent(String cursor, Instant occurredAt, JsonNode data) {
        public long numericCursor() {
            return Long.parseLong(cursor);
        }
    }

    public record EventWindow(List<RealtimeEvent> events, long globalCursor) {
    }
}
