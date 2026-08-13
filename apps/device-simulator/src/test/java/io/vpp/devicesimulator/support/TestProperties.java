package io.vpp.devicesimulator.support;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.vpp.devicesimulator.config.SimulatorProperties;
import io.vpp.devicesimulator.simulation.FaultType;
import io.vpp.devicesimulator.simulation.Scenario;

public final class TestProperties {
    public static final UUID TENANT_ID = UUID.fromString("7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941");

    private TestProperties() {
    }

    public static SimulatorProperties defaults(int deviceCount) {
        return with(deviceCount, Scenario.SUNNY_WEEKDAY, 42,
                new SimulatorProperties.Fault(FaultType.NONE, 0, 0, Duration.ofMinutes(5)));
    }

    public static SimulatorProperties with(
            int deviceCount,
            Scenario scenario,
            long seed,
            SimulatorProperties.Fault fault) {
        return new SimulatorProperties(true, seed, TENANT_ID, deviceCount, scenario,
                Instant.parse("2026-08-12T00:00:00Z"), Duration.ofSeconds(5),
                Duration.ofSeconds(15), 0, SimulatorProperties.Protocol.MQTT,
                new SimulatorProperties.Mqtt("127.0.0.1", 1883, Duration.ofSeconds(1)),
                new SimulatorProperties.Tcp("127.0.0.1", 18081, false, false,
                        "test-only", Duration.ofSeconds(1)),
                fault);
    }
}
