package io.vpp.iotgateway.runtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.vpp.iotgateway.config.GatewayProperties;
import io.vpp.iotgateway.identity.DeviceAuthorizer;
import io.vpp.iotgateway.ingress.BoundedIngressExecutor;
import io.vpp.iotgateway.mqtt.MqttGatewayClient;
import io.vpp.iotgateway.session.DeviceSessionRegistry;
import io.vpp.iotgateway.tcp.TcpGatewayServer;

@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayStatusController {
    private final GatewayProperties properties;
    private final MqttGatewayClient mqttClient;
    private final TcpGatewayServer tcpServer;
    private final DeviceAuthorizer authorizer;
    private final DeviceSessionRegistry sessions;
    private final BoundedIngressExecutor executor;

    public GatewayStatusController(
            GatewayProperties properties,
            MqttGatewayClient mqttClient,
            TcpGatewayServer tcpServer,
            DeviceAuthorizer authorizer,
            DeviceSessionRegistry sessions,
            BoundedIngressExecutor executor) {
        this.properties = properties;
        this.mqttClient = mqttClient;
        this.tcpServer = tcpServer;
        this.authorizer = authorizer;
        this.sessions = sessions;
        this.executor = executor;
    }

    @GetMapping("/status")
    public GatewayStatus status() {
        return new GatewayStatus(properties.mqtt().enabled(), mqttClient.isConnected(),
                properties.tcp().enabled(), tcpServer.isRunning(), authorizer.configuredDeviceCount(),
                sessions.activeSessions(), sessions.mqttSessions(), sessions.tcpSessions(),
                executor.queueDepth());
    }
}
