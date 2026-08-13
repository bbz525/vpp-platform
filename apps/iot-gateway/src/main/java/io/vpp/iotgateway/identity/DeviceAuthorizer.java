package io.vpp.iotgateway.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.vpp.iotgateway.config.GatewayProperties;

@Component
public class DeviceAuthorizer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DeviceAuthorizer.class);
    private final GatewayProperties properties;
    private final WebClient webClient;
    private volatile Map<DeviceIdentity, CredentialCheck> credentials = new ConcurrentHashMap<>();
    private volatile Instant lastSuccessfulRefresh;

    public DeviceAuthorizer(GatewayProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder().build();
        if (properties.identity().localDevEnabled()) {
            Map<DeviceIdentity, CredentialCheck> local = new ConcurrentHashMap<>();
            properties.identity().devices().forEach(device -> local.put(
                    new DeviceIdentity(device.tenantId(), device.deviceId()),
                    new PlainCredential(device.credential().getBytes(StandardCharsets.UTF_8))));
            credentials = Map.copyOf(local);
            lastSuccessfulRefresh = Instant.now();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.identity().localDevEnabled()) {
            refreshFromControlPlane();
        }
    }

    @Scheduled(fixedDelayString = "${gateway.identity.refresh-interval:2s}")
    public void scheduledRefresh() {
        if (properties.identity().localDevEnabled()) {
            return;
        }
        try {
            refreshFromControlPlane();
        } catch (RuntimeException exception) {
            log.warn("Device identity snapshot refresh failed reason={}",
                    exception.getClass().getSimpleName());
        }
    }

    public boolean isAuthorized(DeviceIdentity identity) {
        return credentials.containsKey(identity);
    }

    public Optional<DeviceIdentity> authenticate(
            DeviceIdentity identity, String presentedCredential) {
        CredentialCheck expected = credentials.get(identity);
        if (expected == null || presentedCredential == null) {
            return Optional.empty();
        }
        return expected.matches(presentedCredential) ? Optional.of(identity) : Optional.empty();
    }

    public int configuredDeviceCount() {
        return credentials.size();
    }

    public boolean isReady() {
        Instant refreshed = lastSuccessfulRefresh;
        return refreshed != null && refreshed.isAfter(
                Instant.now().minus(properties.identity().refreshInterval().multipliedBy(3)));
    }

    private void refreshFromControlPlane() {
        var snapshots = webClient.get()
                .uri(properties.identity().controlPlaneUrl() + "/api/v1/internal/device-identities")
                .header("X-VPP-Internal-Token", properties.identity().controlPlaneToken())
                .retrieve().bodyToFlux(IdentitySnapshot.class).collectList()
                .block(properties.identity().refreshInterval());
        if (snapshots == null) {
            throw new IllegalStateException("identity snapshot response was empty");
        }
        Map<DeviceIdentity, CredentialCheck> replacement = new ConcurrentHashMap<>();
        for (IdentitySnapshot snapshot : snapshots) {
            replacement.put(new DeviceIdentity(snapshot.tenantId(), snapshot.deviceId()),
                    HmacCredential.parse(snapshot.credentialVerifier(),
                            properties.identity().controlPlaneToken()));
        }
        credentials = Map.copyOf(replacement);
        lastSuccessfulRefresh = Instant.now();
    }

    private sealed interface CredentialCheck permits PlainCredential, HmacCredential {
        boolean matches(String presented);
    }

    private record PlainCredential(byte[] expected) implements CredentialCheck {
        private PlainCredential {
            expected = expected.clone();
        }

        @Override
        public boolean matches(String presented) {
            return MessageDigest.isEqual(expected,
                    presented.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record HmacCredential(byte[] expected, byte[] pepper) implements CredentialCheck {
        private HmacCredential {
            expected = expected.clone();
            pepper = pepper.clone();
        }

        static HmacCredential parse(String verifier, String token) {
            if (verifier == null || !verifier.startsWith("hmac-sha256$")) {
                throw new IllegalArgumentException("unsupported credential verifier");
            }
            return new HmacCredential(Base64.getDecoder().decode(verifier.substring(12)),
                    token.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean matches(String presented) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
                return MessageDigest.isEqual(expected,
                        mac.doFinal(presented.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                return false;
            }
        }
    }

    private record IdentitySnapshot(
            @JsonProperty("tenant_id") java.util.UUID tenantId,
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("credential_verifier") String credentialVerifier,
            @JsonProperty("config_version") long configVersion) {
    }
}
