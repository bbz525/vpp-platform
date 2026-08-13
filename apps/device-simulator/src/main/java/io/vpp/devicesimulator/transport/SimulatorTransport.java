package io.vpp.devicesimulator.transport;

import java.util.Collection;
import java.util.function.Consumer;

import io.vpp.devicesimulator.protocol.CommandAck;
import io.vpp.devicesimulator.protocol.HeartbeatPayload;
import io.vpp.devicesimulator.protocol.TelemetryPayload;
import io.vpp.devicesimulator.simulation.DeviceProfile;

public interface SimulatorTransport extends AutoCloseable {
    String name();

    void start(Collection<DeviceProfile> devices, Consumer<InboundCommand> commandConsumer);

    void publishTelemetry(DeviceProfile device, TelemetryPayload payload);

    void publishHeartbeat(DeviceProfile device, HeartbeatPayload payload);

    void publishCommandAck(DeviceProfile device, CommandAck payload);

    default void disconnect(DeviceProfile device) {
    }

    @Override
    void close();
}
