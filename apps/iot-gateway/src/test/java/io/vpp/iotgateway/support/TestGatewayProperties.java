package io.vpp.iotgateway.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.vpp.iotgateway.config.GatewayProperties;

public final class TestGatewayProperties {
    public static final UUID TENANT_ID = UUID.fromString("7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941");

    private TestGatewayProperties() {
    }

    public static GatewayProperties defaults() {
        return withIngress(1, 4, 2);
    }

    public static GatewayProperties withIngress(int workers, int queueCapacity, int maxInFlight) {
        return new GatewayProperties("local",
                new GatewayProperties.Mqtt(false, "127.0.0.1", 1883, "gateway-test", "", "",
                        false, Duration.ofSeconds(1)),
                new GatewayProperties.Tcp(false, "127.0.0.1", 18081, false, "", "",
                        Duration.ofSeconds(30)),
                new GatewayProperties.Ingress(workers, queueCapacity, maxInFlight, 65_536),
                new GatewayProperties.Commands(false, "gateway-test"),
                new GatewayProperties.Kafka("vpp.telemetry.raw.v1", "vpp.command.requests.v1",
                        "vpp.command.events.v1"),
                new GatewayProperties.Identity(true, "http://127.0.0.1:8080",
                        "local-dev-platform-internal-token-only", Duration.ofSeconds(2), List.of(
                        new GatewayProperties.DeviceCredential(TENANT_ID, "bess-001",
                                "local-dev-tcp-only"))));
    }
}
