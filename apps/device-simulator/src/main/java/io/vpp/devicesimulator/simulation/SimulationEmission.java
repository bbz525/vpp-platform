package io.vpp.devicesimulator.simulation;

import io.vpp.devicesimulator.protocol.HeartbeatPayload;
import io.vpp.devicesimulator.protocol.TelemetryPayload;

public record SimulationEmission(
        DeviceProfile device,
        TelemetryPayload telemetry,
        HeartbeatPayload heartbeat,
        FaultType fault,
        boolean disconnected,
        boolean duplicate) {
}
