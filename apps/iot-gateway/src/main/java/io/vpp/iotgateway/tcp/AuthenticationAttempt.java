package io.vpp.iotgateway.tcp;

import io.vpp.iotgateway.identity.DeviceIdentity;

public record AuthenticationAttempt(DeviceIdentity identity, String credential) {
}
