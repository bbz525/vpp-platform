package io.vpp.iotgateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.SendResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.IngressProtocol;
import io.vpp.iotgateway.support.TestGatewayProperties;

class BoundedKafkaEventPublisherTest {

    @Test
    void rejectsBeyondConfiguredInFlightLimitAndReleasesPermitOnCompletion() {
        FakeSender sender = new FakeSender();
        var publisher = new BoundedKafkaEventPublisher(sender,
                TestGatewayProperties.withIngress(1, 4, 2), new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-08-12T01:00:00Z"), ZoneOffset.UTC));
        var identity = new DeviceIdentity(TestGatewayProperties.TENANT_ID, "bess-001");

        assertThat(publisher.publishTelemetry(identity, IngressProtocol.MQTT, new byte[] {1}))
                .isTrue();
        assertThat(publisher.publishTelemetry(identity, IngressProtocol.MQTT, new byte[] {2}))
                .isTrue();
        assertThat(publisher.publishTelemetry(identity, IngressProtocol.MQTT, new byte[] {3}))
                .isFalse();
        assertThat(publisher.availablePermits()).isZero();

        sender.futures.getFirst().complete(null);
        assertThat(publisher.availablePermits()).isEqualTo(1);
        assertThat(sender.records.getFirst().key()).isEqualTo(identity.kafkaKey());
        assertThat(sender.records.getFirst().headers().lastHeader("vpp-protocol")).isNotNull();
    }

    private static final class FakeSender implements AsyncKafkaSender {
        private final List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
        private final List<CompletableFuture<SendResult<String, byte[]>>> futures = new ArrayList<>();

        @Override
        public CompletableFuture<SendResult<String, byte[]>> send(
                ProducerRecord<String, byte[]> record) {
            records.add(record);
            var future = new CompletableFuture<SendResult<String, byte[]>>();
            futures.add(future);
            return future;
        }
    }
}
