package io.vpp.devicesimulator.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vpp.devicesimulator.protocol.CommandAck;
import io.vpp.devicesimulator.protocol.CommandRequest;
import io.vpp.devicesimulator.simulation.DeviceFleetFactory;
import io.vpp.devicesimulator.simulation.DeviceState;
import io.vpp.devicesimulator.simulation.ScenarioEngine;
import io.vpp.devicesimulator.support.TestProperties;

class CommandProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private CommandProcessor processor;
    private Map<String, DeviceState> fleet;

    @BeforeEach
    void setUp() {
        processor = new CommandProcessor(Clock.fixed(NOW, ZoneOffset.UTC));
        fleet = new DeviceFleetFactory().create(TestProperties.TENANT_ID, 4);
    }

    @Test
    void duplicateIdempotencyKeyReturnsTerminalAckWithoutExecutingTwice() {
        CommandRequest command = command("bess-001", "SET_POWER", Map.of("active_power_kw", 40.0));

        List<CommandAck> first = processor.handle(command, fleet);
        List<CommandAck> replay = processor.handle(command, fleet);

        assertThat(first).extracting(CommandAck::status)
                .containsExactly(CommandAck.Status.ACCEPTED, CommandAck.Status.EXECUTING,
                        CommandAck.Status.SUCCEEDED);
        assertThat(replay).containsExactly(first.getLast());
        assertThat(processor.executionCount()).isEqualTo(1);
        assertThat(fleet.get("bess-001").snapshot().activePowerKw()).isEqualTo(40.0);
        assertThat(first.getLast().actual()).containsEntry("data_quality_source", "SIMULATED");

        var nextEmission = new ScenarioEngine().generate(fleet, TestProperties.defaults(4), 1)
                .stream().filter(item -> item.device().deviceId().equals("bess-001"))
                .findFirst().orElseThrow();
        assertThat(nextEmission.telemetry().metrics().get("active_power_kw")).isEqualTo(40.0);
    }

    @Test
    void outOfRangeBatteryCommandFailsWithoutChangingPower() {
        CommandRequest command = command("bess-001", "SET_POWER",
                Map.of("active_power_kw", 101.0));

        List<CommandAck> acknowledgements = processor.handle(command, fleet);

        assertThat(acknowledgements.getLast().status()).isEqualTo(CommandAck.Status.FAILED);
        assertThat(acknowledgements.getLast().reasonCode()).isEqualTo("POWER_LIMIT_EXCEEDED");
        assertThat(fleet.get("bess-001").snapshot().activePowerKw()).isZero();
        assertThat(processor.executionCount()).isZero();
    }

    @Test
    void commandForWrongDeviceTypeIsRejected() {
        CommandRequest command = command("pv-001", "SET_POWER", Map.of("active_power_kw", 20.0));

        CommandAck terminal = processor.handle(command, fleet).getLast();

        assertThat(terminal.status()).isEqualTo(CommandAck.Status.FAILED);
        assertThat(terminal.reasonCode()).isEqualTo("ACTION_NOT_SUPPORTED_BY_DEVICE");
    }

    @Test
    void stopClearsAnExistingBatteryPowerOverrideAndIsIdempotent() {
        processor.handle(command("bess-001", "SET_POWER", Map.of("active_power_kw", 40.0)), fleet);
        CommandRequest stop = command("bess-001", "STOP", Map.of());

        List<CommandAck> first = processor.handle(stop, fleet);
        List<CommandAck> replay = processor.handle(stop, fleet);

        assertThat(first).extracting(CommandAck::status)
                .containsExactly(CommandAck.Status.ACCEPTED, CommandAck.Status.EXECUTING,
                        CommandAck.Status.SUCCEEDED);
        assertThat(first.getLast().actual()).containsEntry("active_power_kw", 0);
        assertThat(replay).containsExactly(first.getLast());
        assertThat(fleet.get("bess-001").snapshot().activePowerKw()).isZero();
        assertThat(processor.executionCount()).isEqualTo(2);
    }

    private static CommandRequest command(String deviceId, String action, Map<String, Object> parameters) {
        UUID commandId = UUID.randomUUID();
        return new CommandRequest("vpp.command.requested", 1, commandId,
                "schedule-42:v1:" + deviceId + ':' + action, TestProperties.TENANT_ID, deviceId,
                action, parameters, NOW.minusSeconds(1), NOW.plusSeconds(60), 1,
                NOW.minusSeconds(5));
    }
}
