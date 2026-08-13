package io.vpp.iotgateway.config;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway")
public record GatewayProperties(
        String environment,
        Mqtt mqtt,
        Tcp tcp,
        Ingress ingress,
        Commands commands,
        Kafka kafka,
        Identity identity) {

    public GatewayProperties {
        boolean production = "production".equalsIgnoreCase(environment);
        if (production && identity.localDevEnabled()) {
            throw new IllegalArgumentException(
                    "gateway local identity registry is forbidden in production");
        }
        if (production && mqtt.enabled() && !mqtt.tls()) {
            throw new IllegalArgumentException("gateway MQTT TLS is required in production");
        }
        if (production && tcp.enabled() && !tcp.tls()) {
            throw new IllegalArgumentException("gateway TCP TLS is required in production");
        }
        if (production && !identity.localDevEnabled()
                && !identity.controlPlaneUrl().startsWith("https://")) {
            throw new IllegalArgumentException("gateway control plane URL must use HTTPS in production");
        }
    }

    public record Mqtt(
            boolean enabled,
            String host,
            int port,
            String clientId,
            String username,
            String password,
            boolean tls,
            Duration connectTimeout) {
        public Mqtt {
            requireText(host, "gateway.mqtt.host");
            requirePort(port, "gateway.mqtt.port");
            requireText(clientId, "gateway.mqtt.client-id");
        }
    }

    public record Tcp(
            boolean enabled,
            String bindHost,
            int port,
            boolean tls,
            String certificateChain,
            String privateKey,
            Duration idleTimeout) {
        public Tcp {
            requireText(bindHost, "gateway.tcp.bind-host");
            requirePort(port, "gateway.tcp.port");
            if (!tls && !isLoopback(bindHost)) {
                throw new IllegalArgumentException(
                        "plaintext gateway TCP is allowed only on a loopback bind host");
            }
            if (tls && (isBlank(certificateChain) || isBlank(privateKey))) {
                throw new IllegalArgumentException(
                        "gateway TCP certificate chain and private key are required when TLS is enabled");
            }
            if (idleTimeout.isZero() || idleTimeout.isNegative()) {
                throw new IllegalArgumentException("gateway.tcp.idle-timeout must be positive");
            }
        }
    }

    public record Ingress(
            int workerThreads,
            int queueCapacity,
            int maxInFlightKafka,
            int maxPayloadBytes) {
        public Ingress {
            if (workerThreads < 1 || workerThreads > 256) {
                throw new IllegalArgumentException("gateway.ingress.worker-threads must be 1..256");
            }
            if (queueCapacity < 1 || maxInFlightKafka < 1) {
                throw new IllegalArgumentException("gateway ingress capacity values must be positive");
            }
            if (maxPayloadBytes < 1 || maxPayloadBytes > 65_536) {
                throw new IllegalArgumentException(
                        "gateway.ingress.max-payload-bytes must be between 1 and 65536");
            }
        }
    }

    public record Commands(boolean enabled, String groupId) {
        public Commands {
            requireText(groupId, "gateway.commands.group-id");
        }
    }

    public record Kafka(
            String rawTelemetryTopic,
            String commandRequestsTopic,
            String commandEventsTopic) {
        public Kafka {
            requireText(rawTelemetryTopic, "gateway.kafka.raw-telemetry-topic");
            requireText(commandRequestsTopic, "gateway.kafka.command-requests-topic");
            requireText(commandEventsTopic, "gateway.kafka.command-events-topic");
        }
    }

    public record Identity(
            boolean localDevEnabled,
            String controlPlaneUrl,
            String controlPlaneToken,
            Duration refreshInterval,
            List<DeviceCredential> devices) {
        public Identity {
            controlPlaneUrl = controlPlaneUrl == null ? "" : controlPlaneUrl;
            controlPlaneToken = controlPlaneToken == null ? "" : controlPlaneToken;
            devices = devices == null ? List.of() : List.copyOf(devices);
            if (localDevEnabled && devices.isEmpty()) {
                throw new IllegalArgumentException(
                        "gateway local identity registry requires at least one device");
            }
            if (!localDevEnabled) {
                requireText(controlPlaneUrl, "gateway.identity.control-plane-url");
                requireText(controlPlaneToken, "gateway.identity.control-plane-token");
            }
            if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) {
                throw new IllegalArgumentException("gateway identity refresh interval must be positive");
            }
        }
    }

    public record DeviceCredential(UUID tenantId, String deviceId, String credential) {
        public DeviceCredential {
            requireText(deviceId, "gateway.identity.devices.device-id");
            requireText(credential, "gateway.identity.devices.credential");
        }
    }

    private static void requireText(String value, String property) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(property + " cannot be blank");
        }
    }

    private static void requirePort(int port, String property) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(property + " must be between 1 and 65535");
        }
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
