package io.vpp.devicesimulator.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.vpp.devicesimulator.config.SimulatorProperties;
import io.vpp.devicesimulator.support.TestProperties;

class ScenarioEngineTest {
    private final DeviceFleetFactory fleetFactory = new DeviceFleetFactory();
    private final ScenarioEngine engine = new ScenarioEngine();

    @Test
    void sameSeedProducesReplayableThirtyMinuteCurveForOneHundredDevices() {
        SimulatorProperties properties = TestProperties.defaults(100);
        Map<String, DeviceState> firstFleet = fleetFactory.create(properties.tenantId(), 100);
        Map<String, DeviceState> replayFleet = fleetFactory.create(properties.tenantId(), 100);

        for (long tick = 1; tick <= 360; tick++) {
            List<SimulationEmission> first = engine.generate(firstFleet, properties, tick);
            List<SimulationEmission> replay = engine.generate(replayFleet, properties, tick);

            assertThat(first).isEqualTo(replay).hasSize(100);
            assertThat(first).allSatisfy(emission -> {
                assertThat(emission.telemetry().extensions())
                        .containsEntry("data_quality_source", "SIMULATED");
                assertThat(emission.heartbeat().extensions())
                        .containsEntry("data_quality_source", "SIMULATED");
                assertWithinPhysicalBounds(emission);
            });
        }
    }

    @Test
    void batterySocAndPowerStayInsideLimitsAcrossAFullDay() {
        SimulatorProperties properties = TestProperties.defaults(4);
        Map<String, DeviceState> fleet = fleetFactory.create(properties.tenantId(), 4);

        for (long tick = 1; tick <= Duration.ofDays(1).dividedBy(properties.tickInterval()); tick++) {
            engine.generate(fleet, properties, tick).forEach(this::assertWithinPhysicalBounds);
        }
    }

    @Test
    void faultInjectionIsExplicitAndDoesNotMutateInternalSafetyBounds() {
        SimulatorProperties properties = TestProperties.with(4, Scenario.SUNNY_WEEKDAY, 42,
                new SimulatorProperties.Fault(FaultType.OUT_OF_RANGE, 2, 2,
                        Duration.ofMinutes(5)));
        Map<String, DeviceState> fleet = fleetFactory.create(properties.tenantId(), 4);

        SimulationEmission emission = engine.generate(fleet, properties, 2).get(2);

        assertThat(emission.fault()).isEqualTo(FaultType.OUT_OF_RANGE);
        assertThat(emission.telemetry().extensions()).containsEntry("injected_fault", "OUT_OF_RANGE");
        assertThat(emission.telemetry().metrics().get("soc_pct")).isEqualTo(105.0);
        assertThat(fleet.get(emission.device().deviceId()).snapshot().socPct())
                .isBetween(emission.device().minSocPct(), emission.device().maxSocPct());
    }

    @ParameterizedTest
    @MethodSource("transportFaults")
    void transportFaultsAreDeterministicAndExplicit(FaultType faultType) {
        SimulatorProperties properties = TestProperties.with(4, Scenario.SUNNY_WEEKDAY, 42,
                new SimulatorProperties.Fault(faultType, 2, 0, Duration.ofMinutes(5)));

        SimulationEmission emission = engine.generate(
                fleetFactory.create(properties.tenantId(), 4), properties, 2).getFirst();

        assertThat(emission.fault()).isEqualTo(faultType);
        assertThat(emission.telemetry().extensions()).containsEntry("injected_fault", faultType.name());
        if (faultType == FaultType.DISCONNECT) {
            assertThat(emission.disconnected()).isTrue();
        } else if (faultType == FaultType.DUPLICATE) {
            assertThat(emission.duplicate()).isTrue();
        } else if (faultType == FaultType.CLOCK_DRIFT) {
            assertThat(emission.telemetry().deviceTime()).isEqualTo(
                    properties.startTime().plus(properties.tickInterval().multipliedBy(2))
                            .plus(properties.fault().clockDrift()));
        }
    }

    @Test
    void sunnyAndCloudyScenariosProduceDifferentNoonPvOutput() {
        long noonTick = Duration.ofHours(12).dividedBy(Duration.ofSeconds(5));
        SimulatorProperties sunny = TestProperties.with(2, Scenario.SUNNY_WEEKDAY, 42,
                new SimulatorProperties.Fault(FaultType.NONE, 0, 0, Duration.ZERO));
        SimulatorProperties cloudy = TestProperties.with(2, Scenario.CLOUDY_WEEKDAY, 42,
                new SimulatorProperties.Fault(FaultType.NONE, 0, 0, Duration.ZERO));

        double sunnyPower = engine.generate(fleetFactory.create(sunny.tenantId(), 2), sunny, noonTick)
                .get(1).telemetry().metrics().get("active_power_kw");
        double cloudyPower = engine.generate(fleetFactory.create(cloudy.tenantId(), 2), cloudy,
                noonTick).get(1).telemetry().metrics().get("active_power_kw");

        assertThat(sunnyPower).isGreaterThan(cloudyPower);
    }

    private void assertWithinPhysicalBounds(SimulationEmission emission) {
        DeviceProfile device = emission.device();
        if (emission.fault() != FaultType.OUT_OF_RANGE) {
            assertThat(Math.abs(emission.telemetry().metrics().get("active_power_kw")))
                    .isLessThanOrEqualTo(device.ratedPowerKw());
        }
        if (device.type() == DeviceType.BATTERY && emission.fault() != FaultType.OUT_OF_RANGE) {
            assertThat(emission.telemetry().metrics().get("soc_pct"))
                    .isBetween(device.minSocPct(), device.maxSocPct());
        }
    }

    private static Stream<FaultType> transportFaults() {
        return Stream.of(FaultType.DISCONNECT, FaultType.CLOCK_DRIFT, FaultType.DUPLICATE);
    }
}
