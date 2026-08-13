package io.vpp.devicesimulator.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SimulatorPropertiesTest {

    @Test
    void rejectsPlaintextTcpToNonLoopbackHost() {
        assertThatThrownBy(() -> new SimulatorProperties.Tcp(
                "192.0.2.10", 18081, false, false, "credential", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plaintext");
    }

    @Test
    void rejectsInsecureTrustManagerForNonLoopbackHost() {
        assertThatThrownBy(() -> new SimulatorProperties.Tcp(
                "192.0.2.10", 18081, true, true, "credential", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insecure-trust-all");
    }
}
