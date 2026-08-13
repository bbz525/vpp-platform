package io.vpp.devicesimulator.simulation;

public enum DeviceType {
    METER("meter"),
    PV_INVERTER("pv"),
    BATTERY("bess"),
    EV_CHARGER("evse");

    private final String idPrefix;

    DeviceType(String idPrefix) {
        this.idPrefix = idPrefix;
    }

    public String idPrefix() {
        return idPrefix;
    }
}
