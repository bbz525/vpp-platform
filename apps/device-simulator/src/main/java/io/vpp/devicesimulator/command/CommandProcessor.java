package io.vpp.devicesimulator.command;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.vpp.devicesimulator.protocol.CommandAck;
import io.vpp.devicesimulator.protocol.CommandRequest;
import io.vpp.devicesimulator.simulation.DeviceState;
import io.vpp.devicesimulator.simulation.DeviceType;

@Component
public class CommandProcessor {
    private static final int MAX_IDEMPOTENCY_ENTRIES = 10_000;
    private final Clock clock;
    private final Map<String, CommandAck> terminalByIdempotencyKey = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CommandAck> eldest) {
            return size() > MAX_IDEMPOTENCY_ENTRIES;
        }
    };
    private final AtomicLong executionCount = new AtomicLong();

    public CommandProcessor() {
        this(Clock.systemUTC());
    }

    CommandProcessor(Clock clock) {
        this.clock = clock;
    }

    public synchronized List<CommandAck> handle(CommandRequest command, Map<String, DeviceState> fleet) {
        CommandAck prior = terminalByIdempotencyKey.get(command.idempotencyKey());
        if (prior != null) {
            return List.of(prior);
        }
        Instant now = clock.instant();
        String validationFailure = validate(command, fleet, now);
        if (validationFailure != null) {
            CommandAck failed = ack(command, now, CommandAck.Status.FAILED, validationFailure,
                    "Command rejected by simulator safety validation", simulatedActual(Map.of()));
            terminalByIdempotencyKey.put(command.idempotencyKey(), failed);
            return List.of(failed);
        }

        CommandAck accepted = ack(command, now, CommandAck.Status.ACCEPTED, null, null,
                simulatedActual(Map.of()));
        CommandAck executing = ack(command, now, CommandAck.Status.EXECUTING, null, null,
                simulatedActual(Map.of()));
        DeviceState state = fleet.get(command.deviceId());
        try {
            Map<String, Object> actual = execute(command, state);
            executionCount.incrementAndGet();
            CommandAck succeeded = ack(command, now, CommandAck.Status.SUCCEEDED, null, null,
                    simulatedActual(actual));
            terminalByIdempotencyKey.put(command.idempotencyKey(), succeeded);
            return List.of(accepted, executing, succeeded);
        } catch (IllegalArgumentException exception) {
            CommandAck failed = ack(command, now, CommandAck.Status.FAILED, exception.getMessage(),
                    "Command violates simulated device capability", simulatedActual(Map.of()));
            terminalByIdempotencyKey.put(command.idempotencyKey(), failed);
            return List.of(accepted, executing, failed);
        }
    }

    public long executionCount() {
        return executionCount.get();
    }

    private static String validate(
            CommandRequest command, Map<String, DeviceState> fleet, Instant now) {
        if (command.schemaVersion() != 1 || !"vpp.command.requested".equals(command.schema())) {
            return "UNSUPPORTED_SCHEMA";
        }
        DeviceState state = fleet.get(command.deviceId());
        if (state == null || !state.profile().tenantId().equals(command.tenantId())) {
            return "UNKNOWN_DEVICE";
        }
        if (now.isBefore(command.notBefore())) {
            return "NOT_YET_VALID";
        }
        if (!now.isBefore(command.expiresAt())) {
            return "COMMAND_EXPIRED";
        }
        return null;
    }

    private static Map<String, Object> execute(CommandRequest command, DeviceState state) {
        return switch (command.action()) {
            case "SET_POWER" -> {
                requireType(state, DeviceType.BATTERY);
                double power = number(command.parameters(), "active_power_kw");
                state.setPower(power);
                yield Map.of("active_power_kw", power);
            }
            case "STOP" -> {
                requireType(state, DeviceType.BATTERY);
                state.setPower(0);
                yield Map.of("active_power_kw", 0);
            }
            case "SET_ACTIVE_POWER_LIMIT" -> {
                requireType(state, DeviceType.PV_INVERTER);
                double limit = number(command.parameters(), "active_power_kw");
                state.setPowerLimit(limit);
                yield Map.of("active_power_limit_kw", limit);
            }
            case "SET_CHARGE_LIMIT" -> {
                requireType(state, DeviceType.EV_CHARGER);
                double limit = number(command.parameters(), "active_power_kw");
                state.setPowerLimit(limit);
                yield Map.of("active_power_limit_kw", limit);
            }
            case "PAUSE" -> {
                requireType(state, DeviceType.EV_CHARGER);
                state.pause();
                yield Map.of("paused", true);
            }
            case "RESUME" -> {
                requireType(state, DeviceType.EV_CHARGER);
                state.resume();
                yield Map.of("paused", false);
            }
            default -> throw new IllegalArgumentException("UNSUPPORTED_ACTION");
        };
    }

    private static void requireType(DeviceState state, DeviceType expected) {
        if (state.profile().type() != expected) {
            throw new IllegalArgumentException("ACTION_NOT_SUPPORTED_BY_DEVICE");
        }
    }

    private static double number(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("INVALID_PARAMETER");
        }
        return number.doubleValue();
    }

    private static Map<String, Object> simulatedActual(Map<String, Object> actual) {
        Map<String, Object> marked = new LinkedHashMap<>(actual);
        marked.put("data_quality_source", "SIMULATED");
        return Map.copyOf(marked);
    }

    private static CommandAck ack(
            CommandRequest command,
            Instant now,
            CommandAck.Status status,
            String reasonCode,
            String message,
            Map<String, Object> actual) {
        UUID eventId = UUID.nameUUIDFromBytes(
                (command.commandId() + ":" + status).getBytes(StandardCharsets.UTF_8));
        return new CommandAck(1, eventId, command.commandId(), command.idempotencyKey(), now,
                status, reasonCode, message, actual);
    }
}
