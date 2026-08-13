package io.vpp.devicesimulator.simulation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.vpp.devicesimulator.config.SimulatorProperties;
import io.vpp.devicesimulator.protocol.HeartbeatPayload;
import io.vpp.devicesimulator.protocol.TelemetryPayload;

@Component
public class ScenarioEngine {

    public List<SimulationEmission> generate(
            Map<String, DeviceState> fleet,
            SimulatorProperties properties,
            long tick) {
        List<SimulationEmission> emissions = new ArrayList<>(fleet.size());
        double elapsedHours = properties.tickInterval().toMillis() / 3_600_000.0;
        Instant nominalTime = properties.startTime().plus(properties.tickInterval().multipliedBy(tick));
        for (DeviceState state : fleet.values()) {
            DeviceProfile device = state.profile();
            FaultType fault = properties.fault().appliesTo(device.deviceIndex(), tick)
                    ? properties.fault().type() : FaultType.NONE;
            Instant deviceTime = fault == FaultType.CLOCK_DRIFT
                    ? nominalTime.plus(properties.fault().clockDrift()) : nominalTime;
            double power = scenarioPower(device, properties.scenario(), nominalTime,
                    noise(properties.seed(), device.deviceId(), tick));
            state.update(power, elapsedHours);
            DeviceState.Snapshot snapshot = state.snapshot();
            Map<String, Double> metrics = metrics(device, snapshot, nominalTime);
            if (fault == FaultType.OUT_OF_RANGE) {
                metrics.put("active_power_kw", round(device.ratedPowerKw() * 1.5));
                if (device.type() == DeviceType.BATTERY) {
                    metrics.put("soc_pct", 105.0);
                }
            }
            Map<String, Object> extensions = new LinkedHashMap<>();
            extensions.put("simulator_scenario", scenarioName(properties.scenario()));
            extensions.put("device_type", device.type().name());
            extensions.put("data_quality_source", "SIMULATED");
            if (fault != FaultType.NONE) {
                extensions.put("injected_fault", fault.name());
            }
            long telemetrySequence = tick * 2;
            TelemetryPayload telemetry = new TelemetryPayload(1,
                    deterministicUuid(device.deviceId(), telemetrySequence, "telemetry"),
                    telemetrySequence, deviceTime, Map.copyOf(metrics), status(device, snapshot),
                    Map.copyOf(extensions));
            long heartbeatSequence = telemetrySequence + 1;
            HeartbeatPayload heartbeat = new HeartbeatPayload(1,
                    deterministicUuid(device.deviceId(), heartbeatSequence, "heartbeat"),
                    heartbeatSequence, deviceTime, "sim-1.0.0",
                    Duration.between(properties.startTime(), nominalTime).toSeconds(),
                    Map.of("data_quality_source", "SIMULATED"));
            emissions.add(new SimulationEmission(device, telemetry, heartbeat, fault,
                    fault == FaultType.DISCONNECT, fault == FaultType.DUPLICATE));
        }
        return List.copyOf(emissions);
    }

    private static double scenarioPower(
            DeviceProfile device, Scenario scenario, Instant time, double noise) {
        double hour = time.atZone(ZoneOffset.UTC).getHour()
                + time.atZone(ZoneOffset.UTC).getMinute() / 60.0;
        return switch (device.type()) {
            case METER -> round(meterLoad(scenario, hour) * (1 + noise * 0.04));
            case PV_INVERTER -> round(pvPower(device, scenario, hour) * (1 + noise * 0.03));
            case BATTERY -> round(batteryPower(hour) * (1 + noise * 0.02));
            case EV_CHARGER -> round(evPower(scenario, hour, device.ratedPowerKw())
                    * (1 + noise * 0.02));
        };
    }

    private static double meterLoad(Scenario scenario, double hour) {
        double base = scenario == Scenario.SUNNY_WEEKEND ? 55 : 75;
        double morning = gaussian(hour, 9, 2.2) * 65;
        double evening = gaussian(hour, 19, 2.8) * 110;
        return base + morning + evening;
    }

    private static double pvPower(DeviceProfile device, Scenario scenario, double hour) {
        double daylight = Math.max(0, Math.sin(Math.PI * (hour - 6) / 12));
        double weatherFactor = scenario == Scenario.CLOUDY_WEEKDAY ? 0.42 : 0.92;
        return device.ratedPowerKw() * daylight * weatherFactor;
    }

    private static double batteryPower(double hour) {
        if (hour >= 10 && hour < 15) {
            return -55;
        }
        if (hour >= 17 && hour < 21) {
            return 70;
        }
        return 0;
    }

    private static double evPower(Scenario scenario, double hour, double ratedPowerKw) {
        if (scenario == Scenario.SUNNY_WEEKEND) {
            return hour >= 11 && hour < 17 ? ratedPowerKw * 0.55 : 0;
        }
        return hour >= 8 && hour < 18 ? ratedPowerKw * 0.7 : 0;
    }

    private static Map<String, Double> metrics(
            DeviceProfile device, DeviceState.Snapshot state, Instant time) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("active_power_kw", round(state.activePowerKw()));
        switch (device.type()) {
            case METER -> metrics.put("energy_import_kwh", round(state.cumulativeEnergyKwh()));
            case PV_INVERTER -> {
                metrics.put("energy_generated_kwh", round(state.cumulativeEnergyKwh()));
                metrics.put("irradiance_w_m2", round(1000 * Math.max(0,
                        Math.sin(Math.PI * (time.atZone(ZoneOffset.UTC).getHour() - 6) / 12.0))));
            }
            case BATTERY -> {
                metrics.put("soc_pct", round(state.socPct()));
                metrics.put("available_capacity_kwh", round(device.capacityKwh()
                        * (state.socPct() - device.minSocPct()) / 100));
            }
            case EV_CHARGER -> metrics.put("session_energy_kwh",
                    round(state.cumulativeEnergyKwh()));
        }
        return metrics;
    }

    private static String status(DeviceProfile device, DeviceState.Snapshot snapshot) {
        if (snapshot.paused()) {
            return "PAUSED";
        }
        if (Math.abs(snapshot.activePowerKw()) < 0.001) {
            return "IDLE";
        }
        if (device.type() == DeviceType.BATTERY && snapshot.activePowerKw() < 0) {
            return "CHARGING";
        }
        return "RUNNING";
    }

    private static String scenarioName(Scenario scenario) {
        return scenario.name().toLowerCase().replace('_', '-');
    }

    private static double noise(long seed, String deviceId, long tick) {
        long mixed = seed ^ ((long) deviceId.hashCode() << 32) ^ tick * 0x9E3779B97F4A7C15L;
        return new SplittableRandom(mixed).nextDouble(-1, 1);
    }

    private static double gaussian(double value, double center, double width) {
        double normalized = (value - center) / width;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private static UUID deterministicUuid(String deviceId, long sequence, String kind) {
        return UUID.nameUUIDFromBytes(
                (deviceId + ':' + sequence + ':' + kind).getBytes(StandardCharsets.UTF_8));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
