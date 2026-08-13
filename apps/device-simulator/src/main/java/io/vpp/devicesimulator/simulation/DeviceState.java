package io.vpp.devicesimulator.simulation;

import java.util.concurrent.locks.ReentrantLock;

public final class DeviceState {
    private final DeviceProfile profile;
    private final ReentrantLock lock = new ReentrantLock();
    private double activePowerKw;
    private double socPct;
    private double cumulativeEnergyKwh;
    private double powerLimitKw;
    private boolean paused;
    private Double powerOverrideKw;

    public DeviceState(DeviceProfile profile, double initialSocPct) {
        this.profile = profile;
        this.socPct = initialSocPct;
        this.powerLimitKw = profile.ratedPowerKw();
    }

    public DeviceProfile profile() {
        return profile;
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(activePowerKw, socPct, cumulativeEnergyKwh, powerLimitKw, paused);
        } finally {
            lock.unlock();
        }
    }

    public void update(double requestedPowerKw, double elapsedHours) {
        lock.lock();
        try {
            double effectivePower = powerOverrideKw == null ? requestedPowerKw : powerOverrideKw;
            double boundedPower = Math.max(-profile.ratedPowerKw(),
                    Math.min(profile.ratedPowerKw(), effectivePower));
            boundedPower = Math.max(-powerLimitKw, Math.min(powerLimitKw, boundedPower));
            if (paused) {
                boundedPower = 0;
            }
            if (profile.type() == DeviceType.BATTERY) {
                double nextSoc = socPct - boundedPower * elapsedHours / profile.capacityKwh() * 100;
                if (nextSoc < profile.minSocPct()) {
                    boundedPower = Math.min(0, boundedPower);
                    nextSoc = Math.max(profile.minSocPct(),
                            socPct - boundedPower * elapsedHours / profile.capacityKwh() * 100);
                } else if (nextSoc > profile.maxSocPct()) {
                    boundedPower = Math.max(0, boundedPower);
                    nextSoc = Math.min(profile.maxSocPct(),
                            socPct - boundedPower * elapsedHours / profile.capacityKwh() * 100);
                }
                socPct = nextSoc;
            }
            activePowerKw = boundedPower;
            cumulativeEnergyKwh += Math.abs(boundedPower) * elapsedHours;
        } finally {
            lock.unlock();
        }
    }

    public void setPower(double powerKw) {
        lock.lock();
        try {
            if (Math.abs(powerKw) > profile.ratedPowerKw()) {
                throw new IllegalArgumentException("POWER_LIMIT_EXCEEDED");
            }
            activePowerKw = powerKw;
            powerOverrideKw = powerKw;
            paused = false;
        } finally {
            lock.unlock();
        }
    }

    public void setPowerLimit(double powerLimitKw) {
        lock.lock();
        try {
            if (powerLimitKw < 0 || powerLimitKw > profile.ratedPowerKw()) {
                throw new IllegalArgumentException("POWER_LIMIT_EXCEEDED");
            }
            this.powerLimitKw = powerLimitKw;
            activePowerKw = Math.max(-powerLimitKw, Math.min(powerLimitKw, activePowerKw));
        } finally {
            lock.unlock();
        }
    }

    public void pause() {
        lock.lock();
        try {
            paused = true;
            activePowerKw = 0;
        } finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try {
            paused = false;
        } finally {
            lock.unlock();
        }
    }

    public record Snapshot(
            double activePowerKw,
            double socPct,
            double cumulativeEnergyKwh,
            double powerLimitKw,
            boolean paused) {
    }
}
