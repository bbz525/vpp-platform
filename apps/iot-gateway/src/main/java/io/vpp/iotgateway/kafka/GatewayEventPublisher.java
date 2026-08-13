package io.vpp.iotgateway.kafka;

import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.IngressProtocol;

public interface GatewayEventPublisher {
    boolean publishTelemetry(DeviceIdentity identity, IngressProtocol protocol, byte[] payload);

    boolean publishCommandAck(DeviceIdentity identity, IngressProtocol protocol, byte[] payload);
}
