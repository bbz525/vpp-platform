package io.vpp.iotgateway.ingress;

import java.util.function.Consumer;

import io.vpp.iotgateway.identity.DeviceIdentity;

public interface DeviceIngress {
    boolean accept(
            DeviceIdentity identity,
            IngressProtocol protocol,
            IngressMessageType type,
            byte[] payload,
            Consumer<String> rejectionCallback);
}
