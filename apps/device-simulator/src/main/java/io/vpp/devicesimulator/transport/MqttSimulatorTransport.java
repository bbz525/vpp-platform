package io.vpp.devicesimulator.transport;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import io.vpp.devicesimulator.config.SimulatorProperties;
import io.vpp.devicesimulator.protocol.CommandAck;
import io.vpp.devicesimulator.protocol.HeartbeatPayload;
import io.vpp.devicesimulator.protocol.TelemetryPayload;
import io.vpp.devicesimulator.simulation.DeviceProfile;
import tools.jackson.databind.ObjectMapper;

@Component
public class MqttSimulatorTransport implements SimulatorTransport {
    private final SimulatorProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Mqtt3AsyncClient> clients = new ConcurrentHashMap<>();

    public MqttSimulatorTransport(SimulatorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "mqtt";
    }

    @Override
    public void start(Collection<DeviceProfile> devices, Consumer<InboundCommand> commandConsumer) {
        for (DeviceProfile device : devices) {
            Mqtt3AsyncClient client = MqttClient.builder()
                    .useMqttVersion3()
                    .identifier("sim-" + device.deviceId())
                    .serverHost(properties.mqtt().host())
                    .serverPort(properties.mqtt().port())
                    .automaticReconnectWithDefaultConfig()
                    .buildAsync();
            clients.put(device.deviceId(), client);
            connect(client);
            client.subscribeWith()
                    .topicFilter(topic(device, "down/command"))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback(publish -> commandConsumer.accept(
                            new InboundCommand(device.deviceId(), publish.getPayloadAsBytes())))
                    .send()
                    .orTimeout(properties.mqtt().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        }
    }

    @Override
    public void publishTelemetry(DeviceProfile device, TelemetryPayload payload) {
        publish(device, "up/telemetry", payload);
    }

    @Override
    public void publishHeartbeat(DeviceProfile device, HeartbeatPayload payload) {
        publish(device, "up/heartbeat", payload);
    }

    @Override
    public void publishCommandAck(DeviceProfile device, CommandAck payload) {
        publish(device, "up/command-ack", payload);
    }

    @Override
    public void disconnect(DeviceProfile device) {
        Mqtt3AsyncClient client = clients.get(device.deviceId());
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    @Override
    public void close() {
        clients.values().forEach(client -> {
            if (client.getState().isConnected()) {
                client.disconnect().exceptionally(ignored -> null).join();
            }
        });
        clients.clear();
    }

    private void publish(DeviceProfile device, String suffix, Object payload) {
        Mqtt3AsyncClient client = requiredClient(device);
        connect(client);
        try {
            client.publishWith()
                    .topic(topic(device, suffix))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(false)
                    .payload(objectMapper.writeValueAsBytes(payload))
                    .send()
                    .orTimeout(properties.mqtt().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception exception) {
            throw new TransportException("MQTT publish failed for " + device.deviceId(), exception);
        }
    }

    private Mqtt3AsyncClient requiredClient(DeviceProfile device) {
        Mqtt3AsyncClient client = clients.get(device.deviceId());
        if (client == null) {
            throw new TransportException("MQTT client was not started for " + device.deviceId());
        }
        return client;
    }

    private void connect(Mqtt3AsyncClient client) {
        if (!client.getState().isConnected()) {
            client.connectWith()
                    .cleanSession(true)
                    .keepAlive(30)
                    .send()
                    .orTimeout(properties.mqtt().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        }
    }

    private static String topic(DeviceProfile device, String suffix) {
        return "vpp/%s/%s/%s".formatted(device.tenantId(), device.deviceId(), suffix);
    }
}
