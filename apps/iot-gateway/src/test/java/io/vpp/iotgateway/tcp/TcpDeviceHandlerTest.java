package io.vpp.iotgateway.tcp;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.vpp.iotgateway.identity.DeviceAuthorizer;
import io.vpp.iotgateway.ingress.DeviceIngress;
import io.vpp.iotgateway.session.DeviceSessionRegistry;
import io.vpp.iotgateway.support.TestGatewayProperties;
import tools.jackson.databind.json.JsonMapper;

class TcpDeviceHandlerTest {
    private DeviceSessionRegistry sessions;
    private DeviceIngress ingress;
    private TcpDeviceHandler handler;

    @BeforeEach
    void setUp() {
        var properties = TestGatewayProperties.defaults();
        sessions = new DeviceSessionRegistry();
        ingress = (identity, protocol, type, payload, rejection) -> true;
        handler = new TcpDeviceHandler(new TcpAuthenticationParser(JsonMapper.builder().build()),
                new DeviceAuthorizer(properties), ingress, sessions, new SimpleMeterRegistry());
    }

    @Test
    void closesConnectionWhenBusinessFrameArrivesBeforeAuthentication() {
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(new VppFrame(VppFrame.TELEMETRY, 1, 0, "{}".getBytes()));

        assertThat(channel.isActive()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void authenticatesThenAcceptsStrictlyIncreasingBusinessFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertThat(channel.writeInbound(authFrame("local-dev-tcp-only"))).isFalse();
        VppFrame authOk = channel.readOutbound();
        assertThat(authOk.messageType()).isEqualTo(VppFrame.AUTH_OK);
        assertThat(sessions.tcpSessions()).isEqualTo(1);

        assertThat(channel.writeInbound(new VppFrame(VppFrame.TELEMETRY, 1, 0,
                "{}".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(channel.isActive()).isTrue();

        channel.writeInbound(new VppFrame(VppFrame.HEARTBEAT, 1, 0,
                "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(channel.isActive()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsWrongCredentialWithoutEchoingIt() {
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(authFrame("wrong-credential"));

        assertThat(channel.isActive()).isFalse();
        Object outbound = channel.readOutbound();
        assertThat(outbound).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void acceptsReauthenticationAfterConnectionRestart() {
        EmbeddedChannel first = new EmbeddedChannel(handler);
        first.writeInbound(authFrame("local-dev-tcp-only"));
        first.readOutbound();
        assertThat(sessions.tcpSessions()).isEqualTo(1);

        first.close();
        assertThat(sessions.tcpSessions()).isZero();

        EmbeddedChannel second = new EmbeddedChannel(handler);
        second.writeInbound(authFrame("local-dev-tcp-only"));
        VppFrame authOk = second.readOutbound();
        assertThat(authOk.messageType()).isEqualTo(VppFrame.AUTH_OK);
        assertThat(second.isActive()).isTrue();
        assertThat(sessions.tcpSessions()).isEqualTo(1);

        first.finishAndReleaseAll();
        second.finishAndReleaseAll();
    }

    private static VppFrame authFrame(String credential) {
        String payload = """
                {"device_id":"bess-001",
                 "tenant_id":"7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941",
                 "credential":"%s",
                 "client_nonce":"c9e88c7c-17af-4931-a1f3-a45f1b096de7"}
                """.formatted(credential);
        return new VppFrame(VppFrame.AUTH, 0, 0, payload.getBytes(StandardCharsets.UTF_8));
    }
}
