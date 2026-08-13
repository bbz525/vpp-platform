package io.vpp.iotgateway.mqtt;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.IngressMessageType;

public record MqttTopic(DeviceIdentity identity, IngressMessageType messageType) {
    private static final Pattern DEVICE_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");

    public static Optional<MqttTopic> parseUpstream(String topic) {
        String[] parts = topic.split("/", -1);
        if (parts.length != 5 || !"vpp".equals(parts[0]) || !"up".equals(parts[3])
                || !DEVICE_ID.matcher(parts[2]).matches()) {
            return Optional.empty();
        }
        try {
            IngressMessageType type = switch (parts[4]) {
                case "telemetry" -> IngressMessageType.TELEMETRY;
                case "heartbeat" -> IngressMessageType.HEARTBEAT;
                case "command-ack" -> IngressMessageType.COMMAND_ACK;
                default -> null;
            };
            return type == null ? Optional.empty() : Optional.of(new MqttTopic(
                    new DeviceIdentity(UUID.fromString(parts[1]), parts[2]), type));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
