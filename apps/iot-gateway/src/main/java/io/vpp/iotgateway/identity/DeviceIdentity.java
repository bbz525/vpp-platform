package io.vpp.iotgateway.identity;

import java.util.UUID;

public record DeviceIdentity(UUID tenantId, String deviceId) {
    public String kafkaKey() {
        return tenantId + ":" + deviceId;
    }
}
