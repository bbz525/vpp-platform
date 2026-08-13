package io.vpp.devicesimulator.config;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vpp.devicesimulator.simulation.FaultType;
import io.vpp.devicesimulator.simulation.Scenario;

@ConfigurationProperties("simulator")
public record SimulatorProperties(
        boolean enabled,
        long seed,
        UUID tenantId,
        int deviceCount,
        Scenario scenario,
        Instant startTime,
        Duration tickInterval,
        Duration heartbeatInterval,
        long maxTicks,
        Protocol protocol,
        Mqtt mqtt,
        Tcp tcp,
        Fault fault) {

    public SimulatorProperties {
        if (deviceCount < 1 || deviceCount > 10_000) {
            throw new IllegalArgumentException("simulator.device-count must be between 1 and 10000");
        }
        if (tickInterval.isZero() || tickInterval.isNegative()) {
            throw new IllegalArgumentException("simulator.tick-interval must be positive");
        }
        if (heartbeatInterval.compareTo(tickInterval) < 0) {
            throw new IllegalArgumentException("simulator.heartbeat-interval cannot be shorter than tick-interval");
        }
        if (maxTicks < 0) {
            throw new IllegalArgumentException("simulator.max-ticks cannot be negative");
        }
        if (fault.type() != FaultType.NONE && fault.targetDeviceIndex() >= deviceCount) {
            throw new IllegalArgumentException(
                    "simulator.fault.target-device-index must reference an existing device");
        }
    }

    public enum Protocol {
        MQTT,
        TCP,
        BOTH
    }

    public record Mqtt(String host, int port, Duration connectTimeout) {
        public Mqtt {
            requireHost(host, "simulator.mqtt.host");
            requirePort(port, "simulator.mqtt.port");
        }
    }

    public record Tcp(
            String host,
            int port,
            boolean tls,
            boolean insecureTrustAll,
            String credential,
            Duration connectTimeout) {
        public Tcp {
            requireHost(host, "simulator.tcp.host");
            requirePort(port, "simulator.tcp.port");
            if (insecureTrustAll && !isLoopback(host)) {
                throw new IllegalArgumentException(
                        "simulator.tcp.insecure-trust-all is allowed only for a loopback host");
            }
            if (!tls && !isLoopback(host)) {
                throw new IllegalArgumentException("plaintext simulator TCP is allowed only for a loopback host");
            }
        }
    }

    public record Fault(
            FaultType type,
            long everyNthTick,
            int targetDeviceIndex,
            Duration clockDrift) {
        public Fault {
            if (everyNthTick < 0) {
                throw new IllegalArgumentException("simulator.fault.every-nth-tick cannot be negative");
            }
            if (targetDeviceIndex < 0) {
                throw new IllegalArgumentException("simulator.fault.target-device-index cannot be negative");
            }
        }

        public boolean appliesTo(int deviceIndex, long tick) {
            return type != FaultType.NONE
                    && deviceIndex == targetDeviceIndex
                    && everyNthTick > 0
                    && tick > 0
                    && tick % everyNthTick == 0;
        }
    }

    private static void requireHost(String host, String property) {
        if (host == null || host.isBlank()) {
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
}
