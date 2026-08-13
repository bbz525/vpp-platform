package io.vpp.iotgateway.tcp;

public record VppFrame(int messageType, long sequence, long sentAtEpochMs, byte[] payload) {
    public static final int AUTH = 1;
    public static final int TELEMETRY = 2;
    public static final int HEARTBEAT = 3;
    public static final int DEVICE_EVENT = 4;
    public static final int COMMAND_ACK = 5;
    public static final int AUTH_OK = 100;
    public static final int COMMAND = 101;
    public static final int CONFIG = 102;

    public VppFrame {
        payload = payload.clone();
        if (payload.length > 65_536) {
            throw new IllegalArgumentException("TCP payload exceeds 65536 bytes");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
