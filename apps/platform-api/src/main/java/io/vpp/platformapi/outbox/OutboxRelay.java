package io.vpp.platformapi.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.vpp.platformapi.config.OutboxProperties;

@Component
@ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final OutboxProperties properties;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka,
            OutboxProperties properties) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval:1s}")
    @Transactional
    public void relay() {
        List<PendingEvent> events = jdbc.query("""
                SELECT id,topic,message_key,payload_json::text
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY created_at,id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """, (rs, row) -> new PendingEvent(rs.getObject("id", UUID.class),
                rs.getString("topic"), rs.getString("message_key"),
                rs.getString("payload_json")), properties.batchSize());
        for (PendingEvent event : events) {
            publish(event);
        }
    }

    private void publish(PendingEvent event) {
        try {
            kafka.send(event.topic(), event.key(), event.payload().getBytes(StandardCharsets.UTF_8))
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            jdbc.update("UPDATE outbox_event SET published_at=?,attempts=attempts+1,last_error_code=NULL WHERE id=?",
                    java.sql.Timestamp.from(Instant.now()), event.id());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(event, exception);
        } catch (Exception exception) {
            recordFailure(event, exception);
        }
    }

    private void recordFailure(PendingEvent event, Exception exception) {
        String code = exception.getClass().getSimpleName();
        jdbc.update("UPDATE outbox_event SET attempts=attempts+1,last_error_code=? WHERE id=?",
                code, event.id());
        log.warn("Outbox publish failed id={} topic={} code={}", event.id(), event.topic(), code);
    }

    private record PendingEvent(UUID id, String topic, String key, String payload) {}
}
