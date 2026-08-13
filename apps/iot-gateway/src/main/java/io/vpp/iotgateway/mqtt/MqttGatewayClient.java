package io.vpp.iotgateway.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vpp.iotgateway.config.GatewayProperties;
import io.vpp.iotgateway.ingress.DeviceIngress;
import io.vpp.iotgateway.ingress.IngressProtocol;

@Component
public class MqttGatewayClient implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MqttGatewayClient.class);
    private final GatewayProperties properties;
    private final DeviceIngress ingressService;
    private final Counter malformedTopics;
    private volatile Mqtt3AsyncClient client;

    public MqttGatewayClient(
            GatewayProperties properties,
            DeviceIngress ingressService,
            MeterRegistry registry) {
        this.properties = properties;
        this.ingressService = ingressService;
        this.malformedTopics = registry.counter("vpp.gateway.mqtt.rejected",
                "reason", "malformed_topic");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.mqtt().enabled()) {
            log.info("Gateway MQTT ingestion is disabled");
            return;
        }
        Mqtt3ClientBuilder builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(properties.mqtt().clientId())
                .serverHost(properties.mqtt().host())
                .serverPort(properties.mqtt().port())
                .automaticReconnectWithDefaultConfig();
        if (properties.mqtt().tls()) {
            builder.sslWithDefaultConfig();
        }
        client = builder.buildAsync();
        var connect = client.connectWith().cleanSession(false).keepAlive(30);
        if (properties.mqtt().username() != null && !properties.mqtt().username().isBlank()) {
            connect.simpleAuth()
                    .username(properties.mqtt().username())
                    .password(properties.mqtt().password().getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth();
        }
        connect.send()
                .orTimeout(properties.mqtt().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .join();
        for (String filter : List.of("vpp/+/+/up/telemetry", "vpp/+/+/up/heartbeat",
                "vpp/+/+/up/command-ack")) {
            client.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE)
                    .callback(this::onPublish).send()
                    .orTimeout(properties.mqtt().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        }
        log.info("Gateway MQTT ingestion connected to {}:{}", properties.mqtt().host(),
                properties.mqtt().port());
    }

    public boolean publishCommand(io.vpp.iotgateway.identity.DeviceIdentity identity, byte[] payload) {
        Mqtt3AsyncClient active = client;
        if (active == null || !active.getState().isConnected()) {
            return false;
        }
        try {
            active.publishWith()
                    .topic("vpp/%s/%s/down/command".formatted(identity.tenantId(),
                            identity.deviceId()))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(false)
                    .payload(payload.clone())
                    .send()
                    .orTimeout(properties.mqtt().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean isConnected() {
        Mqtt3AsyncClient active = client;
        return active != null && active.getState().isConnected();
    }

    @PreDestroy
    public void close() {
        Mqtt3AsyncClient active = client;
        if (active != null && active.getState().isConnected()) {
            active.disconnect().exceptionally(ignored -> null).join();
        }
    }

    private void onPublish(Mqtt3Publish publish) {
        String topicText = publish.getTopic().toString();
        MqttTopic.parseUpstream(topicText).ifPresentOrElse(topic ->
                        ingressService.accept(topic.identity(), IngressProtocol.MQTT,
                                topic.messageType(), publish.getPayloadAsBytes(), ignored -> { }),
                () -> {
                    malformedTopics.increment();
                    log.warn("Rejected malformed MQTT topic");
                });
    }
}
