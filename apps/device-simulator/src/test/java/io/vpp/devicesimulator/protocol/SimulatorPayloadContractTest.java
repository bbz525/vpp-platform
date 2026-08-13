package io.vpp.devicesimulator.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.vpp.devicesimulator.simulation.DeviceFleetFactory;
import io.vpp.devicesimulator.simulation.ScenarioEngine;
import io.vpp.devicesimulator.support.TestProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class SimulatorPayloadContractTest {
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatedPayloadUsesTheSharedMqttFieldNamesAndSimulationMarker() throws Exception {
        var properties = TestProperties.defaults(4);
        var fleet = new DeviceFleetFactory().create(properties.tenantId(), 4);
        var emission = new ScenarioEngine().generate(fleet, properties, 1).getFirst();

        Map<String, Object> telemetry = objectMapper.readValue(
                objectMapper.writeValueAsBytes(emission.telemetry()), new TypeReference<>() { });
        Map<String, Object> heartbeat = objectMapper.readValue(
                objectMapper.writeValueAsBytes(emission.heartbeat()), new TypeReference<>() { });

        assertThat(telemetry).containsKeys("schema_version", "event_id", "sequence",
                "device_time", "metrics", "status", "extensions");
        assertThat(castMap(telemetry.get("extensions")))
                .containsEntry("data_quality_source", "SIMULATED");
        assertThat(heartbeat).containsKeys("schema_version", "event_id", "sequence",
                "device_time", "firmware_version", "uptime_seconds", "extensions");
        assertThat(castMap(heartbeat.get("extensions")))
                .containsEntry("data_quality_source", "SIMULATED");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
