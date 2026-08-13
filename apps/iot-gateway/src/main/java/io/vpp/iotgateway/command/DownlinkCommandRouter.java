package io.vpp.iotgateway.command;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vpp.iotgateway.identity.DeviceAuthorizer;
import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.DevicePayloadValidator;
import io.vpp.iotgateway.ingress.ValidatedCommand;
import io.vpp.iotgateway.mqtt.MqttGatewayClient;
import io.vpp.iotgateway.session.DeviceSessionRegistry;
import io.vpp.iotgateway.tcp.VppFrame;

@Component
public class DownlinkCommandRouter {
    private static final Logger log = LoggerFactory.getLogger(DownlinkCommandRouter.class);
    private final DevicePayloadValidator validator;
    private final DeviceAuthorizer authorizer;
    private final DeviceSessionRegistry sessions;
    private final MqttGatewayClient mqttClient;
    private final Clock clock = Clock.systemUTC();
    private final Map<DeviceIdentity, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Counter routed;
    private final Counter failed;

    public DownlinkCommandRouter(
            DevicePayloadValidator validator,
            DeviceAuthorizer authorizer,
            DeviceSessionRegistry sessions,
            MqttGatewayClient mqttClient,
            MeterRegistry registry) {
        this.validator = validator;
        this.authorizer = authorizer;
        this.sessions = sessions;
        this.mqttClient = mqttClient;
        this.routed = registry.counter("vpp.gateway.commands.routed");
        this.failed = registry.counter("vpp.gateway.commands.failed");
    }

    @KafkaListener(
            topics = "${gateway.kafka.command-requests-topic}",
            groupId = "${gateway.commands.group-id}",
            autoStartup = "${gateway.commands.enabled}")
    public void route(ConsumerRecord<String, byte[]> record) {
        byte[] payload = record.value();
        try {
            ValidatedCommand command = validator.validateCommandRequest(payload);
            if (!authorizer.isAuthorized(command.identity())) {
                throw new IllegalArgumentException("UNAUTHORIZED_DEVICE");
            }
            if (clock.instant().isBefore(command.notBefore())) {
                throw new IllegalArgumentException("COMMAND_NOT_YET_VALID");
            }
            if (!clock.instant().isBefore(command.expiresAt())) {
                throw new IllegalArgumentException("COMMAND_EXPIRED");
            }
            boolean delivered = sessions.activeTcpChannel(command.identity())
                    .map(channel -> {
                        long sequence = sequences.computeIfAbsent(command.identity(), ignored ->
                                new AtomicLong()).incrementAndGet();
                        channel.writeAndFlush(new VppFrame(VppFrame.COMMAND, sequence,
                                clock.millis(), payload));
                        return true;
                    })
                    .orElseGet(() -> mqttClient.publishCommand(command.identity(), payload));
            if (!delivered) {
                throw new IllegalStateException("NO_ACTIVE_DOWNLINK");
            }
            routed.increment();
        } catch (RuntimeException exception) {
            failed.increment();
            log.warn("Command routing failed for Kafka key={} reason={}", record.key(),
                    exception.getMessage());
            throw exception;
        }
    }
}
