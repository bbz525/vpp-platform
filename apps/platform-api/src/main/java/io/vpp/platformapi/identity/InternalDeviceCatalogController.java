package io.vpp.platformapi.identity;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.vpp.platformapi.resource.ResourceRepository;

@RestController
@RequestMapping("/api/v1/internal/device-catalog")
public class InternalDeviceCatalogController {
    private final InternalTokenGuard tokenGuard;
    private final ResourceRepository repository;

    public InternalDeviceCatalogController(InternalTokenGuard tokenGuard,
            ResourceRepository repository) {
        this.tokenGuard = tokenGuard;
        this.repository = repository;
    }

    @GetMapping
    public List<DeviceCatalogEntry> list(@RequestHeader("X-VPP-Internal-Token") String token) {
        tokenGuard.verify(token);
        return repository.deviceCatalog().stream().map(device -> new DeviceCatalogEntry(
                device.tenantId(), device.deviceId(), device.externalCode(), device.siteId(),
                device.portfolioId(), device.deviceType(), device.schemaVersion(),
                device.pointSchema(), device.deviceStatus(), device.configVersion())).toList();
    }

    public record DeviceCatalogEntry(
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("device_id") UUID deviceId,
            @JsonProperty("external_code") String externalCode,
            @JsonProperty("site_id") UUID siteId,
            @JsonProperty("portfolio_id") UUID portfolioId,
            @JsonProperty("device_type") String deviceType,
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("point_schema") JsonNode pointSchema,
            @JsonProperty("device_status") String deviceStatus,
            @JsonProperty("config_version") long configVersion) {
    }
}
