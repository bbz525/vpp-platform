package io.vpp.platformapi.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform")
public record PlatformProperties(
        String environment,
        Auth auth,
        Bootstrap bootstrap,
        String internalToken) {

    private static final Set<String> UNSAFE = Set.of("changeme", "password", "example");

    public PlatformProperties {
        requireText(environment, "platform.environment");
        requireText(internalToken, "platform.internal-token");
        boolean production = "production".equalsIgnoreCase(environment);
        if (production) {
            if (auth.mode() != AuthMode.JWT) {
                throw new IllegalArgumentException("platform JWT authentication is required in production");
            }
            URI issuer = URI.create(auth.jwtIssuerUri());
            if (!"https".equalsIgnoreCase(issuer.getScheme())) {
                throw new IllegalArgumentException("platform JWT issuer must use HTTPS in production");
            }
            if (bootstrap.enabled()) {
                throw new IllegalArgumentException("platform local bootstrap is forbidden in production");
            }
            String token = internalToken.toLowerCase(Locale.ROOT);
            if (internalToken.length() < 32 || UNSAFE.stream().anyMatch(token::contains)) {
                throw new IllegalArgumentException("platform internal token is unsafe for production");
            }
        }
    }

    public record Auth(AuthMode mode, String jwtIssuerUri) {
        public Auth {
            if (mode == null) {
                throw new IllegalArgumentException("platform.auth.mode is required");
            }
            jwtIssuerUri = jwtIssuerUri == null ? "" : jwtIssuerUri;
            if (mode == AuthMode.JWT) {
                requireText(jwtIssuerUri, "platform.auth.jwt-issuer-uri");
            }
        }
    }

    public enum AuthMode {
        LOCAL, JWT
    }

    public record Bootstrap(
            boolean enabled,
            UUID tenantId,
            String tenantName,
            UUID userId,
            String subject) {
        public Bootstrap {
            requireText(tenantName, "platform.bootstrap.tenant-name");
            requireText(subject, "platform.bootstrap.subject");
        }
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " cannot be blank");
        }
    }
}
