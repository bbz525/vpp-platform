package io.vpp.platformapi.identity;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.vpp.platformapi.resource.ResourceRepository;

@RestController
@RequestMapping("/api/v1/internal/device-identities")
public class InternalIdentityController {
    private final InternalTokenGuard tokenGuard;
    private final ResourceRepository repository;

    public InternalIdentityController(InternalTokenGuard tokenGuard, ResourceRepository repository) {
        this.tokenGuard = tokenGuard;
        this.repository = repository;
    }

    @GetMapping
    public List<IdentitySnapshot> list(@RequestHeader("X-VPP-Internal-Token") String token) {
        tokenGuard.verify(token);
        return repository.activeIdentities().stream().map(identity -> new IdentitySnapshot(
                identity.tenantId(), identity.deviceId(), identity.credentialVerifier(),
                identity.configVersion())).toList();
    }

    public record IdentitySnapshot(
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("credential_verifier") String credentialVerifier,
            @JsonProperty("config_version") long configVersion) {
    }
}
