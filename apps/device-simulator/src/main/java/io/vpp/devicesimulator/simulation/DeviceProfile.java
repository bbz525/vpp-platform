package io.vpp.devicesimulator.simulation;

import java.util.UUID;

public record DeviceProfile(
        UUID tenantId,
        String deviceId,
        int deviceIndex,
        DeviceType type,
        double ratedPowerKw,
        double capacityKwh,
        double minSocPct,
        double maxSocPct) {
}
