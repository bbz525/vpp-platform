package io.vpp.iotgateway.runtime;

public record GatewayStatus(
        boolean mqttEnabled,
        boolean mqttConnected,
        boolean tcpEnabled,
        boolean tcpListening,
        int configuredDevices,
        int activeSessions,
        long mqttSessions,
        long tcpSessions,
        int ingressQueueDepth) {
}
