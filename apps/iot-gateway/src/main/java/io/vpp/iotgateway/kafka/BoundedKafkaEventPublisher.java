package io.vpp.iotgateway.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Semaphore;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vpp.iotgateway.config.GatewayProperties;
import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.IngressProtocol;

@Component
public class BoundedKafkaEventPublisher implements GatewayEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(BoundedKafkaEventPublisher.class);
    private final AsyncKafkaSender sender;
    private final GatewayProperties properties;
    private final Semaphore inFlight;
    private final Clock clock;
    private final Counter sent;
    private final Counter failed;
    private final Counter rejected;

    @Autowired
    public BoundedKafkaEventPublisher(
            AsyncKafkaSender sender, GatewayProperties properties, MeterRegistry registry) {
        this(sender, properties, registry, Clock.systemUTC());
    }

    BoundedKafkaEventPublisher(
            AsyncKafkaSender sender,
            GatewayProperties properties,
            MeterRegistry registry,
            Clock clock) {
        this.sender = sender;
        this.properties = properties;
        this.inFlight = new Semaphore(properties.ingress().maxInFlightKafka());
        this.clock = clock;
        this.sent = registry.counter("vpp.gateway.kafka.sent");
        this.failed = registry.counter("vpp.gateway.kafka.failed");
        this.rejected = registry.counter("vpp.gateway.kafka.rejected", "reason", "in_flight_full");
    }

    @Override
    public boolean publishTelemetry(
            DeviceIdentity identity, IngressProtocol protocol, byte[] payload) {
        return publish(properties.kafka().rawTelemetryTopic(), identity, protocol, payload);
    }

    @Override
    public boolean publishCommandAck(
            DeviceIdentity identity, IngressProtocol protocol, byte[] payload) {
        return publish(properties.kafka().commandEventsTopic(), identity, protocol, payload);
    }

    public int availablePermits() {
        return inFlight.availablePermits();
    }

    private boolean publish(
            String topic, DeviceIdentity identity, IngressProtocol protocol, byte[] payload) {
        if (!inFlight.tryAcquire()) {
            rejected.increment();
            return false;
        }
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, null,
                clock.millis(), identity.kafkaKey(), payload.clone(), java.util.List.of(
                        new RecordHeader("vpp-protocol",
                                protocol.name().getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("vpp-received-at",
                                Instant.ofEpochMilli(clock.millis()).toString()
                                        .getBytes(StandardCharsets.UTF_8))));
        try {
            sender.send(record).whenComplete((result, error) -> {
                inFlight.release();
                if (error == null) {
                    sent.increment();
                } else {
                    failed.increment();
                    log.warn("Kafka publish failed for topic={} key={}: {}", topic,
                            identity.kafkaKey(), error.getClass().getSimpleName());
                }
            });
            return true;
        } catch (RuntimeException exception) {
            inFlight.release();
            failed.increment();
            log.warn("Kafka publish rejected synchronously for topic={} key={}: {}", topic,
                    identity.kafkaKey(), exception.getClass().getSimpleName());
            return false;
        }
    }
}
