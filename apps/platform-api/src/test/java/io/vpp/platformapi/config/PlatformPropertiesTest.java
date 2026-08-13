package io.vpp.platformapi.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlatformPropertiesTest {
    @Test
    void rejectsLocalAuthenticationInProduction() {
        assertThatThrownBy(() -> new PlatformProperties("production",
                new PlatformProperties.Auth(PlatformProperties.AuthMode.LOCAL, ""),
                new PlatformProperties.Bootstrap(false, UUID.randomUUID(), "tenant",
                        UUID.randomUUID(), "subject"),
                "a-production-grade-internal-token-123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT");
    }

    @Test
    void rejectsLocalBootstrapInProduction() {
        assertThatThrownBy(() -> new PlatformProperties("production",
                new PlatformProperties.Auth(PlatformProperties.AuthMode.JWT,
                        "https://identity.example.test/issuer"),
                new PlatformProperties.Bootstrap(true, UUID.randomUUID(), "tenant",
                        UUID.randomUUID(), "subject"),
                "a-production-grade-internal-token-123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootstrap");
    }
}
