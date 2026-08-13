package io.vpp.iotgateway.ingress;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.support.TestGatewayProperties;
import tools.jackson.databind.json.JsonMapper;

class DevicePayloadValidatorTest {
    private DevicePayloadValidator validator;
    private DeviceIdentity identity;

    @BeforeEach
    void setUp() {
        validator = new DevicePayloadValidator(JsonMapper.builder().build(),
                TestGatewayProperties.defaults());
        identity = new DeviceIdentity(TestGatewayProperties.TENANT_ID, "bess-001");
    }

    @Test
    void acceptsSharedTelemetrySemantics() {
        assertThatNoException().isThrownBy(() -> validator.validateTelemetry(identity, bytes("""
                {
                  "schema_version": 1,
                  "event_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
                  "sequence": 42,
                  "device_time": "2026-08-12T01:00:00Z",
                  "metrics": {"active_power_kw": 20.0, "soc_pct": 55.0},
                  "status": "RUNNING",
                  "extensions": {"data_quality_source": "SIMULATED"}
                }
                """)));
    }

    @Test
    void rejectsUnknownPointSecretFieldsAndOversizedPayload() {
        assertThatThrownBy(() -> validator.validateTelemetry(identity, bytes("""
                {"schema_version":1,"event_id":"0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
                 "sequence":1,"device_time":"2026-08-12T01:00:00Z",
                 "metrics":{"unknown_kw":1}}
                """))).isInstanceOf(PayloadValidationException.class)
                .hasMessage("UNREGISTERED_METRIC");

        assertThatThrownBy(() -> validator.validateTelemetry(identity, bytes("""
                {"schema_version":1,"event_id":"0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
                 "sequence":1,"device_time":"2026-08-12T01:00:00Z",
                 "metrics":{"active_power_kw":1},"extensions":{"password":"forbidden"}}
                """))).isInstanceOf(PayloadValidationException.class)
                .hasMessage("SECRET_FIELD_FORBIDDEN");

        assertThatThrownBy(() -> validator.validateTelemetry(identity, new byte[65_537]))
                .isInstanceOf(PayloadValidationException.class)
                .hasMessage("PAYLOAD_SIZE_INVALID");
    }

    @Test
    void rejectsInvalidCommandWindow() {
        assertThatThrownBy(() -> validator.validateCommandRequest(bytes("""
                {"schema":"vpp.command.requested","schema_version":1,
                 "command_id":"0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb70",
                 "idempotency_key":"schedule:test:command","tenant_id":"7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941",
                 "device_id":"bess-001","action":"STOP","parameters":{},
                 "not_before":"2026-08-12T02:00:00Z","expires_at":"2026-08-12T01:00:00Z"}
                """))).isInstanceOf(PayloadValidationException.class)
                .hasMessage("INVALID_COMMAND_WINDOW");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
