package io.vpp.iotgateway.ingress;

import java.time.Instant;

import io.vpp.iotgateway.identity.DeviceIdentity;

public record ValidatedCommand(
        DeviceIdentity identity,
        String commandId,
        String idempotencyKey,
        Instant notBefore,
        Instant expiresAt) {
}
