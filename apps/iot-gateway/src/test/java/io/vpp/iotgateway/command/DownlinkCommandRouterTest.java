package io.vpp.iotgateway.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vpp.iotgateway.identity.DeviceAuthorizer;
import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.DevicePayloadValidator;
import io.vpp.iotgateway.mqtt.MqttGatewayClient;
import io.vpp.iotgateway.session.DeviceSessionRegistry;
import io.vpp.iotgateway.support.TestGatewayProperties;
import tools.jackson.databind.json.JsonMapper;

class DownlinkCommandRouterTest {
    private static final DeviceIdentity IDENTITY = new DeviceIdentity(
            TestGatewayProperties.TENANT_ID, "bess-001");

    @Test
    void routesAuthorizedDueCommandToMqttWhenNoTcpSessionExists() {
        DeviceAuthorizer authorizer = mock(DeviceAuthorizer.class);
        MqttGatewayClient mqtt = mock(MqttGatewayClient.class);
        when(authorizer.isAuthorized(IDENTITY)).thenReturn(true);
        byte[] payload = commandPayload(Instant.now().minusSeconds(5), Instant.now().plusSeconds(30));
        when(mqtt.publishCommand(IDENTITY, payload)).thenReturn(true);

        router(authorizer, mqtt).route(record(payload));

        verify(mqtt).publishCommand(IDENTITY, payload);
    }

    @Test
    void rejectsUnauthorizedCommandWithoutTouchingDownlink() {
        DeviceAuthorizer authorizer = mock(DeviceAuthorizer.class);
        MqttGatewayClient mqtt = mock(MqttGatewayClient.class);
        byte[] payload = commandPayload(Instant.now().minusSeconds(5), Instant.now().plusSeconds(30));

        assertThatThrownBy(() -> router(authorizer, mqtt).route(record(payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNAUTHORIZED_DEVICE");

        verify(mqtt, never()).publishCommand(any(), any(byte[].class));
    }

    @Test
    void rejectsMismatchedKafkaKeyBeforeAuthorizationOrDownlink() {
        DeviceAuthorizer authorizer = mock(DeviceAuthorizer.class);
        MqttGatewayClient mqtt = mock(MqttGatewayClient.class);
        byte[] payload = commandPayload(Instant.now().minusSeconds(5), Instant.now().plusSeconds(30));
        ConsumerRecord<String, byte[]> mismatched = new ConsumerRecord<>(
                "vpp.command.requests.v1", 0, 0,
                IDENTITY.tenantId() + ":another-device", payload);

        assertThatThrownBy(() -> router(authorizer, mqtt).route(mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COMMAND_IDENTITY_MISMATCH");

        verify(authorizer, never()).isAuthorized(any());
        verify(mqtt, never()).publishCommand(any(), any(byte[].class));
    }

    @Test
    void rejectsNullKafkaKeyBeforeAuthorizationOrDownlink() {
        DeviceAuthorizer authorizer = mock(DeviceAuthorizer.class);
        MqttGatewayClient mqtt = mock(MqttGatewayClient.class);
        byte[] payload = commandPayload(Instant.now().minusSeconds(5), Instant.now().plusSeconds(30));
        ConsumerRecord<String, byte[]> missing = new ConsumerRecord<>(
                "vpp.command.requests.v1", 0, 0, null, payload);

        assertThatThrownBy(() -> router(authorizer, mqtt).route(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COMMAND_IDENTITY_MISMATCH");

        verify(authorizer, never()).isAuthorized(any());
        verify(mqtt, never()).publishCommand(any(), any(byte[].class));
    }

    @Test
    void rejectsExpiredCommandBeforeDownlink() {
        DeviceAuthorizer authorizer = mock(DeviceAuthorizer.class);
        MqttGatewayClient mqtt = mock(MqttGatewayClient.class);
        when(authorizer.isAuthorized(IDENTITY)).thenReturn(true);
        byte[] payload = commandPayload(Instant.now().minusSeconds(30), Instant.now().minusSeconds(5));

        assertThatThrownBy(() -> router(authorizer, mqtt).route(record(payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COMMAND_EXPIRED");

        verify(mqtt, never()).publishCommand(any(), any(byte[].class));
    }

    private static DownlinkCommandRouter router(DeviceAuthorizer authorizer, MqttGatewayClient mqtt) {
        return new DownlinkCommandRouter(
                new DevicePayloadValidator(JsonMapper.builder().build(),
                        TestGatewayProperties.defaults()),
                authorizer,
                new DeviceSessionRegistry(),
                mqtt,
                new SimpleMeterRegistry());
    }

    private static ConsumerRecord<String, byte[]> record(byte[] payload) {
        return new ConsumerRecord<>("vpp.command.requests.v1", 0, 0,
                IDENTITY.tenantId() + ":" + IDENTITY.deviceId(), payload);
    }

    private static byte[] commandPayload(Instant notBefore, Instant expiresAt) {
        return ("""
                {"schema":"vpp.command.requested","schema_version":1,
                 "command_id":"0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb70",
                 "idempotency_key":"schedule:test:command","tenant_id":"%s",
                 "device_id":"bess-001","action":"SET_POWER",
                 "parameters":{"active_power_kw":20.0,"duration_seconds":15},
                 "not_before":"%s","expires_at":"%s","safety_config_version":1,
                 "created_at":"2026-08-12T10:02:00Z"}
                """).formatted(IDENTITY.tenantId(), notBefore, expiresAt)
                .getBytes(StandardCharsets.UTF_8);
    }
}
