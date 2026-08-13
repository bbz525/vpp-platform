package io.vpp.streamprocessor.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.vpp.streamprocessor.catalog.DeviceCatalog;
import io.vpp.streamprocessor.catalog.DeviceCatalogEntry;
import io.vpp.streamprocessor.config.StreamProperties;
import io.vpp.streamprocessor.normalization.NormalizedResult;
import io.vpp.streamprocessor.normalization.NormalizedTelemetry;
import io.vpp.streamprocessor.normalization.RawTelemetry;
import io.vpp.streamprocessor.normalization.TelemetryNormalizer;
import io.vpp.streamprocessor.normalization.TelemetryValidationException;
import io.vpp.streamprocessor.sink.ClickHouseTelemetryStore;
import io.vpp.streamprocessor.sink.RedisProjectionResult;
import io.vpp.streamprocessor.sink.RedisStateProjector;

@Component
public class TelemetryPipeline {
    private static final Logger log = LoggerFactory.getLogger(TelemetryPipeline.class);

    private final StreamProperties properties;
    private final DeviceCatalog catalog;
    private final TelemetryNormalizer normalizer;
    private final ClickHouseTelemetryStore clickHouse;
    private final RedisStateProjector redis;
    private final KafkaTemplate<String, byte[]> kafka;
    private final ObjectMapper mapper;
    private final Map<String, Instant> catalogMisses = new ConcurrentHashMap<>();
    private final Counter accepted;
    private final Counter duplicates;
    private final Counter invalid;
    private final Counter lateStateIgnored;

    public TelemetryPipeline(StreamProperties properties, DeviceCatalog catalog,
            TelemetryNormalizer normalizer, ClickHouseTelemetryStore clickHouse,
            RedisStateProjector redis, KafkaTemplate<String, byte[]> kafka,
            ObjectMapper mapper, MeterRegistry registry) {
        this.properties = properties;
        this.catalog = catalog;
        this.normalizer = normalizer;
        this.clickHouse = clickHouse;
        this.redis = redis;
        this.kafka = kafka;
        this.mapper = mapper;
        this.accepted = registry.counter("vpp.stream.telemetry.accepted");
        this.duplicates = registry.counter("vpp.stream.telemetry.duplicate");
        this.invalid = registry.counter("vpp.stream.telemetry.invalid");
        this.lateStateIgnored = registry.counter("vpp.stream.state.older_ignored");
    }

    @KafkaListener(id = "telemetryNormalizer", autoStartup = "false",
            topics = "${stream.kafka.raw-topic}", groupId = "${stream.kafka.group-id}")
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        RawTelemetry raw;
        try {
            raw = normalizer.parse(record.value());
        } catch (TelemetryValidationException exception) {
            deadLetter(record, exception.code(), exception.getMessage());
            invalid.increment();
            acknowledgment.acknowledge();
            return;
        }

        DeviceCatalogEntry device = catalog.find(record.key()).orElse(null);
        if (device == null) {
            Instant firstMiss = catalogMisses.computeIfAbsent(record.key(), ignored -> Instant.now());
            if (firstMiss.plus(properties.catalog().missGracePeriod()).isAfter(Instant.now())) {
                throw new IllegalStateException("device catalog entry not available yet");
            }
            deadLetter(record, "UNKNOWN_DEVICE", "device is absent from the control-plane catalog");
            catalogMisses.remove(record.key());
            invalid.increment();
            acknowledgment.acknowledge();
            return;
        }
        catalogMisses.remove(record.key());

        Instant receivedAt = receivedAt(record);
        NormalizedResult result;
        try {
            result = normalizer.normalize(raw, device, receivedAt,
                    Instant.ofEpochMilli(record.timestamp()));
        } catch (TelemetryValidationException exception) {
            deadLetter(record, exception.code(), exception.getMessage());
            invalid.increment();
            acknowledgment.acknowledge();
            return;
        }

        NormalizedTelemetry telemetry = result.telemetry();
        if (redis.wasProcessed(telemetry)) {
            duplicates.increment();
            acknowledgment.acknowledge();
            return;
        }

        clickHouse.store(result, eventVersion(record));
        publish(properties.kafka().normalizedTopic(), record.key(), json(telemetry));
        RedisProjectionResult projection = redis.project(telemetry, device.deviceStatus());
        if (projection == RedisProjectionResult.OLDER_IGNORED) {
            lateStateIgnored.increment();
        } else {
            publishStateEvents(telemetry, device);
        }
        redis.markProcessed(telemetry);
        accepted.increment();
        acknowledgment.acknowledge();
    }

    private void publishStateEvents(NormalizedTelemetry telemetry, DeviceCatalogEntry device) {
        ObjectNode state = mapper.createObjectNode();
        state.put("schema", "vpp.device-state.changed");
        state.put("schema_version", 1);
        state.put("event_id", telemetry.eventId().toString());
        state.put("tenant_id", telemetry.tenantId().toString());
        state.put("device_id", device.deviceId().toString());
        state.put("external_code", device.externalCode());
        state.put("site_id", device.siteId().toString());
        state.put("occurred_at", telemetry.occurredAt().toString());
        state.put("quality_status", telemetry.dataQuality().status());
        state.set("metrics", mapper.valueToTree(telemetry.payload().metrics()));
        publish(properties.kafka().deviceStateTopic(), device.identityKey(), json(state));

        publishAggregate(telemetry, "SITE", device.siteId().toString());
        publishAggregate(telemetry, "PORTFOLIO", device.portfolioId().toString());
    }

    private void publishAggregate(NormalizedTelemetry telemetry, String targetType, String targetId) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schema", "vpp.aggregate.snapshot");
        snapshot.put("schema_version", 1);
        snapshot.put("causation_id", telemetry.eventId().toString());
        snapshot.put("tenant_id", telemetry.tenantId().toString());
        snapshot.put("target_type", targetType);
        snapshot.put("target_id", targetId);
        snapshot.put("occurred_at", telemetry.occurredAt().toString());
        publish(properties.kafka().aggregateSnapshotTopic(),
                telemetry.tenantId() + ":" + targetType.toLowerCase() + ":" + targetId,
                json(snapshot));
    }

    private void deadLetter(ConsumerRecord<String, byte[]> record, String code, String message) {
        ObjectNode dlq = mapper.createObjectNode();
        dlq.put("schema", "vpp.telemetry.dlq");
        dlq.put("schema_version", 1);
        dlq.put("failed_at", Instant.now().toString());
        dlq.put("error_code", code);
        dlq.put("error_message", message);
        dlq.put("source_topic", record.topic());
        dlq.put("source_partition", record.partition());
        dlq.put("source_offset", record.offset());
        dlq.put("message_key", record.key());
        dlq.put("payload_base64", Base64.getEncoder().encodeToString(record.value()));
        publish(properties.kafka().dlqTopic(), record.key(), json(dlq));
        log.warn("Telemetry sent to DLQ key={} code={} partition={} offset={}",
                record.key(), code, record.partition(), record.offset());
    }

    private void publish(String topic, String key, byte[] payload) {
        try {
            kafka.send(topic, key, payload).get(properties.sinkTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publish interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka publish failed", exception);
        }
    }

    private byte[] json(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("event serialization failed", exception);
        }
    }

    private static long eventVersion(ConsumerRecord<String, byte[]> record) {
        return record.offset() + 1;
    }

    private static Instant receivedAt(ConsumerRecord<String, byte[]> record) {
        var header = record.headers().lastHeader("vpp-received-at");
        if (header == null) return Instant.ofEpochMilli(record.timestamp());
        try {
            return Instant.parse(new String(header.value(), StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            return Instant.ofEpochMilli(record.timestamp());
        }
    }
}
