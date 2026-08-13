package io.vpp.platformapi.alarm;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.alarm.AlarmDtos.AlarmResponse;
import io.vpp.platformapi.config.AlarmProperties;

@Component
public class AlarmRealtimePublisher {
    private static final Logger log = LoggerFactory.getLogger(AlarmRealtimePublisher.class);
    private static final DefaultRedisScript<Long> APPEND = new DefaultRedisScript<>("""
            local cursor=redis.call('INCR',KEYS[1])
            local text=string.format('%016d',cursor)
            redis.call('XADD',KEYS[2],'MAXLEN','~',tonumber(ARGV[3]),'*',
              'cursor',text,'occurred_at',ARGV[1],'data',ARGV[2])
            redis.call('EXPIRE',KEYS[2],tonumber(ARGV[4]))
            return cursor
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final AlarmProperties properties;

    public AlarmRealtimePublisher(StringRedisTemplate redis, ObjectMapper mapper,
            AlarmProperties properties) {
        this.redis = redis; this.mapper = mapper; this.properties = properties;
    }

    public void publishAfterCommit(UUID tenantId, AlarmResponse alarm, String eventType, Instant at) {
        if (!properties.enabled()) {
            return;
        }
        Runnable action = () -> publish(tenantId, alarm, eventType, at);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private void publish(UUID tenantId, AlarmResponse alarm, String eventType, Instant at) {
        var data = mapper.createObjectNode();
        data.put("event_type", eventType);
        data.set("alarm", mapper.valueToTree(alarm));
        String prefix = "vpp:{" + tenantId + "}:";
        try {
        redis.execute(APPEND, java.util.List.of(prefix + "ws:alarms:cursor", prefix + "ws:alarms"),
                    at.toString(), json(data), Long.toString(properties.resumeMaxLength()),
                    Long.toString(properties.resumeTtl().toSeconds()));
        } catch (RuntimeException exception) {
            log.warn("Alarm realtime cache unavailable tenant={} alarm={} event={}",
                    tenantId, alarm.id(), eventType);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("cannot serialize alarm realtime event", exception); }
    }
}
