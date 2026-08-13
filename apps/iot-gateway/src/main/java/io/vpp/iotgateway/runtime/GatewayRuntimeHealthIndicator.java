package io.vpp.iotgateway.runtime;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.vpp.iotgateway.config.GatewayProperties;
import io.vpp.iotgateway.mqtt.MqttGatewayClient;
import io.vpp.iotgateway.tcp.TcpGatewayServer;
import io.vpp.iotgateway.identity.DeviceAuthorizer;

@Component("gatewayRuntime")
public class GatewayRuntimeHealthIndicator implements HealthIndicator {
    private final GatewayProperties properties;
    private final MqttGatewayClient mqttClient;
    private final TcpGatewayServer tcpServer;
    private final DeviceAuthorizer authorizer;

    public GatewayRuntimeHealthIndicator(
            GatewayProperties properties,
            MqttGatewayClient mqttClient,
            TcpGatewayServer tcpServer,
            DeviceAuthorizer authorizer) {
        this.properties = properties;
        this.mqttClient = mqttClient;
        this.tcpServer = tcpServer;
        this.authorizer = authorizer;
    }

    @Override
    public Health health() {
        boolean mqttReady = !properties.mqtt().enabled() || mqttClient.isConnected();
        boolean tcpReady = !properties.tcp().enabled() || tcpServer.isRunning();
        boolean identityReady = authorizer.isReady();
        Health.Builder builder = mqttReady && tcpReady && identityReady ? Health.up() : Health.down();
        return builder.withDetail("mqtt", mqttReady).withDetail("tcp", tcpReady)
                .withDetail("identity", identityReady).build();
    }
}
