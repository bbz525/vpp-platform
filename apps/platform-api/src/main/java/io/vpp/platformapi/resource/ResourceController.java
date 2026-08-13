package io.vpp.platformapi.resource;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.vpp.platformapi.resource.ResourceDtos.CreateDeviceModelRequest;
import io.vpp.platformapi.resource.ResourceDtos.CreateDeviceRequest;
import io.vpp.platformapi.resource.ResourceDtos.CreatePortfolioRequest;
import io.vpp.platformapi.resource.ResourceDtos.CreateSiteRequest;
import io.vpp.platformapi.resource.ResourceDtos.CredentialReferenceResponse;
import io.vpp.platformapi.resource.ResourceDtos.DeviceModelResponse;
import io.vpp.platformapi.resource.ResourceDtos.DeviceResponse;
import io.vpp.platformapi.resource.ResourceDtos.PortfolioResponse;
import io.vpp.platformapi.resource.ResourceDtos.RotateCredentialRequest;
import io.vpp.platformapi.resource.ResourceDtos.SiteResponse;
import io.vpp.platformapi.resource.ResourceDtos.UpdateDeviceStatusRequest;
import io.vpp.platformapi.security.ActorResolver;
import io.vpp.platformapi.security.Role;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ResourceController {
    private final ActorResolver actors;
    private final ResourceService service;

    public ResourceController(ActorResolver actors, ResourceService service) {
        this.actors = actors;
        this.service = service;
    }

    @PostMapping("/portfolios")
    @ResponseStatus(HttpStatus.CREATED)
    public PortfolioResponse createPortfolio(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody CreatePortfolioRequest request) {
        return service.createPortfolio(actors.require(Role.TENANT_ADMIN), key, request);
    }

    @GetMapping("/portfolios")
    public List<PortfolioResponse> listPortfolios() {
        return service.listPortfolios(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId());
    }

    @GetMapping("/portfolios/{portfolioId}/sites")
    public List<SiteResponse> listSites(@PathVariable UUID portfolioId) {
        return service.listSites(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId(), portfolioId);
    }

    @PostMapping("/sites")
    @ResponseStatus(HttpStatus.CREATED)
    public SiteResponse createSite(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody CreateSiteRequest request) {
        return service.createSite(actors.require(Role.TENANT_ADMIN), key, request);
    }

    @PostMapping("/device-models")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceModelResponse createModel(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody CreateDeviceModelRequest request) {
        return service.createModel(actors.require(Role.TENANT_ADMIN), key, request);
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse createDevice(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody CreateDeviceRequest request) {
        return service.createDevice(actors.require(Role.TENANT_ADMIN), key, request);
    }

    @GetMapping("/devices")
    public List<DeviceResponse> listDevices() {
        return service.listDevices(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId());
    }

    @GetMapping("/devices/{deviceId}")
    public DeviceResponse getDevice(@PathVariable UUID deviceId) {
        return service.getDevice(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId(), deviceId);
    }

    @PostMapping("/devices/{deviceId}/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    public CredentialReferenceResponse rotateCredential(@PathVariable UUID deviceId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody RotateCredentialRequest request) {
        return service.rotateCredential(actors.require(Role.TENANT_ADMIN), deviceId, key, request);
    }

    @PutMapping("/devices/{deviceId}/status")
    public DeviceResponse updateStatus(@PathVariable UUID deviceId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody UpdateDeviceStatusRequest request) {
        return service.updateStatus(actors.require(Role.TENANT_ADMIN), deviceId, key, request);
    }
}
