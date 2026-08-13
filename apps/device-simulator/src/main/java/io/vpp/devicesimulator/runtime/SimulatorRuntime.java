package io.vpp.devicesimulator.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vpp.devicesimulator.command.CommandProcessor;
import io.vpp.devicesimulator.config.SimulatorProperties;
import io.vpp.devicesimulator.protocol.CommandRequest;
import io.vpp.devicesimulator.simulation.DeviceFleetFactory;
import io.vpp.devicesimulator.simulation.DeviceProfile;
import io.vpp.devicesimulator.simulation.DeviceState;
import io.vpp.devicesimulator.simulation.ScenarioEngine;
import io.vpp.devicesimulator.simulation.SimulationEmission;
import io.vpp.devicesimulator.transport.InboundCommand;
import io.vpp.devicesimulator.transport.MqttSimulatorTransport;
import io.vpp.devicesimulator.transport.SimulatorTransport;
import io.vpp.devicesimulator.transport.TcpSimulatorTransport;
import tools.jackson.databind.ObjectMapper;

@Component
public class SimulatorRuntime implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SimulatorRuntime.class);
    private final SimulatorProperties properties;
    private final ScenarioEngine scenarioEngine;
    private final CommandProcessor commandProcessor;
    private final ObjectMapper objectMapper;
    private final MqttSimulatorTransport mqttTransport;
    private final TcpSimulatorTransport tcpTransport;
    private final Map<String, DeviceState> fleet;
    private final List<SimulatorTransport> activeTransports = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("simulator-tick").factory());
    private final ExecutorService commandExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong currentTick = new AtomicLong();
    private final Counter telemetryPublished;
    private final Counter heartbeatPublished;
    private final Counter commandAcksPublished;
    private final Counter duplicates;
    private final Counter disconnects;
    private final Counter failures;

    public SimulatorRuntime(
            SimulatorProperties properties,
            ScenarioEngine scenarioEngine,
            DeviceFleetFactory fleetFactory,
            CommandProcessor commandProcessor,
            ObjectMapper objectMapper,
            MqttSimulatorTransport mqttTransport,
            TcpSimulatorTransport tcpTransport,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.scenarioEngine = scenarioEngine;
        this.commandProcessor = commandProcessor;
        this.objectMapper = objectMapper;
        this.mqttTransport = mqttTransport;
        this.tcpTransport = tcpTransport;
        this.fleet = fleetFactory.create(properties.tenantId(), properties.deviceCount());
        this.telemetryPublished = meterRegistry.counter("vpp.simulator.telemetry.published");
        this.heartbeatPublished = meterRegistry.counter("vpp.simulator.heartbeat.published");
        this.commandAcksPublished = meterRegistry.counter("vpp.simulator.command.acks.published");
        this.duplicates = meterRegistry.counter("vpp.simulator.fault.duplicates");
        this.disconnects = meterRegistry.counter("vpp.simulator.fault.disconnects");
        this.failures = meterRegistry.counter("vpp.simulator.publish.failures");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) {
            log.info("Device simulator is disabled; set SIMULATOR_ENABLED=true to publish data");
            return;
        }
        selectTransports();
        List<DeviceProfile> profiles = fleet.values().stream().map(DeviceState::profile).toList();
        activeTransports.forEach(transport -> transport.start(profiles,
                command -> commandExecutor.submit(() -> handleCommand(transport, command))));
        scheduler.scheduleAtFixedRate(this::safeTick, 0, properties.tickInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Started {} simulated devices with scenario={}, seed={}, transports={}",
                fleet.size(), properties.scenario(), properties.seed(), transportNames());
    }

    public SimulatorStatus status() {
        long tick = currentTick.get();
        Instant time = properties.startTime().plus(properties.tickInterval().multipliedBy(tick));
        return new SimulatorStatus(properties.enabled(), properties.scenario().name(), properties.seed(),
                fleet.size(), transportNames(), tick, (long) telemetryPublished.count(),
                (long) heartbeatPublished.count(), (long) commandAcksPublished.count(),
                (long) duplicates.count(), (long) disconnects.count(), (long) failures.count(), time);
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        commandExecutor.shutdownNow();
        mqttTransport.close();
        tcpTransport.close();
    }

    private void selectTransports() {
        switch (properties.protocol()) {
            case MQTT -> activeTransports.add(mqttTransport);
            case TCP -> activeTransports.add(tcpTransport);
            case BOTH -> {
                activeTransports.add(mqttTransport);
                activeTransports.add(tcpTransport);
            }
        }
    }

    private void safeTick() {
        try {
            long tick = currentTick.get() + 1;
            if (properties.maxTicks() > 0 && tick > properties.maxTicks()) {
                scheduler.shutdown();
                return;
            }
            currentTick.incrementAndGet();
            boolean heartbeatDue = heartbeatDue(tick);
            for (SimulationEmission emission : scenarioEngine.generate(fleet, properties, tick)) {
                for (SimulatorTransport transport : activeTransports) {
                    publish(transport, emission, heartbeatDue);
                }
            }
        } catch (Exception exception) {
            failures.increment();
            log.error("Simulator tick failed; publishing will continue on the next tick", exception);
        }
    }

    private void publish(
            SimulatorTransport transport, SimulationEmission emission, boolean heartbeatDue) {
        try {
            if (emission.disconnected()) {
                transport.disconnect(emission.device());
                disconnects.increment();
                return;
            }
            transport.publishTelemetry(emission.device(), emission.telemetry());
            telemetryPublished.increment();
            if (emission.duplicate()) {
                transport.publishTelemetry(emission.device(), emission.telemetry());
                telemetryPublished.increment();
                duplicates.increment();
            }
            if (heartbeatDue) {
                transport.publishHeartbeat(emission.device(), emission.heartbeat());
                heartbeatPublished.increment();
            }
        } catch (Exception exception) {
            failures.increment();
            log.warn("{} publish failed for device={}: {}", transport.name(),
                    emission.device().deviceId(), exception.getMessage());
        }
    }

    private void handleCommand(SimulatorTransport transport, InboundCommand inbound) {
        try {
            CommandRequest command = objectMapper.readValue(inbound.payload(), CommandRequest.class);
            if (!inbound.deviceId().equals(command.deviceId())) {
                log.warn("Rejected command whose payload device does not match its transport identity");
                failures.increment();
                return;
            }
            DeviceState device = fleet.get(inbound.deviceId());
            if (device == null) {
                log.warn("Ignored command for unknown transport device={}", inbound.deviceId());
                return;
            }
            commandProcessor.handle(command, fleet).forEach(ack -> {
                transport.publishCommandAck(device.profile(), ack);
                commandAcksPublished.increment();
            });
        } catch (Exception exception) {
            failures.increment();
            log.warn("Rejected malformed command for device={}: {}", inbound.deviceId(),
                    exception.getClass().getSimpleName());
        }
    }

    private boolean heartbeatDue(long tick) {
        long elapsed = Math.multiplyExact(tick, properties.tickInterval().toMillis());
        return elapsed % properties.heartbeatInterval().toMillis() < properties.tickInterval().toMillis();
    }

    private List<String> transportNames() {
        return activeTransports.stream().map(SimulatorTransport::name).toList();
    }
}
