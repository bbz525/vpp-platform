package io.vpp.devicesimulator.runtime;

import java.time.Instant;
import java.util.List;

public record SimulatorStatus(
        boolean enabled,
        String scenario,
        long seed,
        int deviceCount,
        List<String> transports,
        long currentTick,
        long telemetryPublished,
        long heartbeatPublished,
        long commandAcksPublished,
        long injectedDuplicates,
        long injectedDisconnects,
        long publishFailures,
        Instant simulationTime) {
}
