package io.vpp.devicesimulator.simulation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class DeviceFleetFactory {

    public Map<String, DeviceState> create(UUID tenantId, int deviceCount) {
        Map<String, DeviceState> fleet = new LinkedHashMap<>();
        DeviceType[] types = DeviceType.values();
        for (int index = 0; index < deviceCount; index++) {
            DeviceType type = types[index % types.length];
            int ordinal = index / types.length + 1;
            DeviceProfile profile = profile(tenantId, index, type, ordinal);
            fleet.put(profile.deviceId(), new DeviceState(profile, initialSoc(type, ordinal)));
        }
        return fleet;
    }

    private static DeviceProfile profile(UUID tenantId, int index, DeviceType type, int ordinal) {
        return switch (type) {
            case METER -> new DeviceProfile(tenantId, id(type, ordinal), index, type,
                    500, 0, 0, 100);
            case PV_INVERTER -> new DeviceProfile(tenantId, id(type, ordinal), index, type,
                    180, 0, 0, 100);
            case BATTERY -> new DeviceProfile(tenantId, id(type, ordinal), index, type,
                    100, 250, 10, 90);
            case EV_CHARGER -> new DeviceProfile(tenantId, id(type, ordinal), index, type,
                    22, 0, 0, 100);
        };
    }

    private static String id(DeviceType type, int ordinal) {
        return "%s-%03d".formatted(type.idPrefix(), ordinal);
    }

    private static double initialSoc(DeviceType type, int ordinal) {
        return type == DeviceType.BATTERY ? 50 + ordinal % 10 : 0;
    }
}
