package io.vpp.devicesimulator.transport;

public record InboundCommand(String deviceId, byte[] payload) {
    public InboundCommand {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
