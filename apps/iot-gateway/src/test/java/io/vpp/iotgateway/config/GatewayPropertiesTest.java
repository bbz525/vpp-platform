package io.vpp.iotgateway.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class GatewayPropertiesTest {

    @Test
    void rejectsPlaintextTcpOnNonLoopbackBind() {
        assertThatThrownBy(() -> new GatewayProperties.Tcp(true, "0.0.0.0", 18081,
                false, "", "", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void rejectsTlsWithoutCertificateMaterial() {
        assertThatThrownBy(() -> new GatewayProperties.Tcp(true, "0.0.0.0", 18081,
                true, "", "", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("certificate");
    }

    @Test
    void rejectsPlaintextControlPlaneInProduction() {
        var defaults = io.vpp.iotgateway.support.TestGatewayProperties.defaults();
        assertThatThrownBy(() -> new GatewayProperties("production", defaults.mqtt(),
                defaults.tcp(), defaults.ingress(), defaults.commands(), defaults.kafka(),
                new GatewayProperties.Identity(false, "http://platform-api:8080",
                        "production-grade-internal-token-123456789", Duration.ofSeconds(5),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
