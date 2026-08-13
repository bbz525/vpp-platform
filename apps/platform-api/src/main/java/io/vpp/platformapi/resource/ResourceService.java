package io.vpp.platformapi.resource;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vpp.platformapi.audit.AuditOutboxWriter;
import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.common.IdempotencyService;
import io.vpp.platformapi.identity.CredentialEncoder;
import io.vpp.platformapi.resource.ResourceDtos.CreateDeviceModelRequest;
import io.vpp.platformapi.resource.ResourceDtos.CreateDeviceRequest;
import io.vpp.platformapi.resource.ResourceDtos.CreatePortfolioRequest;
import io.vpp.platformapi.resource.ResourceDtos.CreateSiteRequest;
import io.vpp.platformapi.resource.ResourceDtos.CredentialReferenceResponse;
import io.vpp.platformapi.resource.ResourceDtos.DeviceCapabilityInput;
import io.vpp.platformapi.resource.ResourceDtos.DeviceModelResponse;
import io.vpp.platformapi.resource.ResourceDtos.DeviceResponse;
import io.vpp.platformapi.resource.ResourceDtos.PortfolioResponse;
import io.vpp.platformapi.resource.ResourceDtos.RotateCredentialRequest;
import io.vpp.platformapi.resource.ResourceDtos.SiteResponse;
import io.vpp.platformapi.resource.ResourceDtos.UpdateDeviceStatusRequest;
import io.vpp.platformapi.security.ActorPrincipal;

@Service
public class ResourceService {
    private final ResourceRepository repository;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter audit;
    private final CredentialEncoder credentials;

    public ResourceService(ResourceRepository repository, IdempotencyService idempotency,
            AuditOutboxWriter audit, CredentialEncoder credentials) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.credentials = credentials;
    }

    @Transactional
    public PortfolioResponse createPortfolio(ActorPrincipal actor, String key,
            CreatePortfolioRequest request) {
        var replay = idempotency.replay(actor.tenantId(), "CREATE_PORTFOLIO", key, request);
        if (replay.isPresent()) {
            return repository.findPortfolio(actor.tenantId(), replay.get())
                    .orElseThrow(() -> ApiException.notFound("portfolio"));
        }
        UUID id = UUID.randomUUID();
        PortfolioResponse created = repository.insertPortfolio(actor.tenantId(), id,
                request.name().trim());
        idempotency.remember(actor.tenantId(), "CREATE_PORTFOLIO", key, request, id);
        audit.succeeded(actor, "PORTFOLIO_CREATED", "PORTFOLIO", id, null, null, created);
        return created;
    }

    @Transactional
    public SiteResponse createSite(ActorPrincipal actor, String key, CreateSiteRequest request) {
        validateTimezone(request.timezone());
        repository.findPortfolio(actor.tenantId(), request.portfolioId())
                .orElseThrow(() -> ApiException.notFound("portfolio"));
        var replay = idempotency.replay(actor.tenantId(), "CREATE_SITE", key, request);
        if (replay.isPresent()) {
            return repository.findSite(actor.tenantId(), replay.get())
                    .orElseThrow(() -> ApiException.notFound("site"));
        }
        UUID id = UUID.randomUUID();
        SiteResponse created = repository.insertSite(actor.tenantId(), id, request.portfolioId(),
                request.name().trim(), request.timezone(), request.gridConnectionLimitKw());
        idempotency.remember(actor.tenantId(), "CREATE_SITE", key, request, id);
        audit.succeeded(actor, "SITE_CREATED", "SITE", id, null, null, created);
        return created;
    }

    @Transactional
    public DeviceModelResponse createModel(ActorPrincipal actor, String key,
            CreateDeviceModelRequest request) {
        if (!request.pointSchema().isObject() || request.pointSchema().isEmpty()) {
            throw ApiException.validation("point_schema must be a non-empty JSON object");
        }
        var replay = idempotency.replay(actor.tenantId(), "CREATE_DEVICE_MODEL", key, request);
        if (replay.isPresent()) {
            return repository.findModel(actor.tenantId(), replay.get())
                    .orElseThrow(() -> ApiException.notFound("device model"));
        }
        UUID id = UUID.randomUUID();
        DeviceModelResponse created = repository.insertModel(actor.tenantId(), id,
                request.name().trim(), request.type(), request.schemaVersion(), request.pointSchema());
        idempotency.remember(actor.tenantId(), "CREATE_DEVICE_MODEL", key, request, id);
        audit.succeeded(actor, "DEVICE_MODEL_CREATED", "DEVICE_MODEL", id, null, null, created);
        return created;
    }

    @Transactional
    public DeviceResponse createDevice(ActorPrincipal actor, String key, CreateDeviceRequest request) {
        repository.findSite(actor.tenantId(), request.siteId())
                .orElseThrow(() -> ApiException.notFound("site"));
        repository.findModel(actor.tenantId(), request.modelId())
                .orElseThrow(() -> ApiException.notFound("device model"));
        validateCapabilities(request.capabilities());
        var replay = idempotency.replay(actor.tenantId(), "CREATE_DEVICE", key, request);
        if (replay.isPresent()) {
            return getDevice(actor.tenantId(), replay.get());
        }
        UUID id = UUID.randomUUID();
        DeviceResponse created = repository.insertDevice(actor.tenantId(), id, request.siteId(),
                request.modelId(), request.externalCode(), request.name().trim(), request.capabilities());
        idempotency.remember(actor.tenantId(), "CREATE_DEVICE", key, request, id);
        audit.succeeded(actor, "DEVICE_REGISTERED", "DEVICE", id, null, null, created);
        return created;
    }

    public DeviceResponse getDevice(UUID tenantId, UUID id) {
        return repository.findDevice(tenantId, id).orElseThrow(() -> ApiException.notFound("device"));
    }

    public List<DeviceResponse> listDevices(UUID tenantId) {
        return repository.listDevices(tenantId);
    }

    public List<PortfolioResponse> listPortfolios(UUID tenantId) {
        return repository.listPortfolios(tenantId);
    }

    public List<SiteResponse> listSites(UUID tenantId, UUID portfolioId) {
        repository.findPortfolio(tenantId, portfolioId)
                .orElseThrow(() -> ApiException.notFound("portfolio"));
        return repository.listSites(tenantId, portfolioId);
    }

    @Transactional
    public CredentialReferenceResponse rotateCredential(ActorPrincipal actor, UUID deviceId,
            String key, RotateCredentialRequest request) {
        DeviceResponse before = getDevice(actor.tenantId(), deviceId);
        String verifier = credentials.encode(request.credential());
        CredentialOperation operation = new CredentialOperation(verifier, request.reason());
        var replay = idempotency.replay(actor.tenantId(), "ROTATE_DEVICE_CREDENTIAL", key, operation);
        if (replay.isPresent()) {
            return repository.findCredential(actor.tenantId(), replay.get())
                    .orElseThrow(() -> ApiException.notFound("credential reference"));
        }
        UUID credentialId = UUID.randomUUID();
        CredentialReferenceResponse created = repository.rotateCredential(actor.tenantId(), deviceId,
                credentialId, verifier);
        idempotency.remember(actor.tenantId(), "ROTATE_DEVICE_CREDENTIAL", key, operation, credentialId);
        DeviceResponse after = getDevice(actor.tenantId(), deviceId);
        audit.succeeded(actor, "DEVICE_CREDENTIAL_ROTATED", "DEVICE", deviceId, request.reason(),
                before, after);
        return created;
    }

    @Transactional
    public DeviceResponse updateStatus(ActorPrincipal actor, UUID deviceId, String key,
            UpdateDeviceStatusRequest request) {
        DeviceResponse before = getDevice(actor.tenantId(), deviceId);
        var replay = idempotency.replay(actor.tenantId(), "UPDATE_DEVICE_STATUS", key, request);
        if (replay.isPresent()) {
            return getDevice(actor.tenantId(), replay.get());
        }
        if ("ACTIVE".equals(request.status()) && !repository.hasActiveCredential(actor.tenantId(), deviceId)) {
            throw ApiException.validation("an active credential is required before enabling a device");
        }
        DeviceResponse after = repository.updateDeviceStatus(actor.tenantId(), deviceId, request.status());
        idempotency.remember(actor.tenantId(), "UPDATE_DEVICE_STATUS", key, request, deviceId);
        audit.succeeded(actor, "DEVICE_STATUS_CHANGED", "DEVICE", deviceId, request.reason(), before, after);
        return after;
    }

    private static void validateCapabilities(List<DeviceCapabilityInput> capabilities) {
        HashSet<String> names = new HashSet<>();
        for (DeviceCapabilityInput capability : capabilities) {
            if (!names.add(capability.capability())) {
                throw ApiException.validation("device capabilities must be unique");
            }
            if (capability.maxValue().compareTo(capability.minValue()) <= 0) {
                throw ApiException.validation("capability max_value must be greater than min_value");
            }
            if (capability.fallbackValue().compareTo(capability.minValue()) < 0
                    || capability.fallbackValue().compareTo(capability.maxValue()) > 0) {
                throw ApiException.validation("capability fallback_value must be inside bounds");
            }
        }
    }

    private static void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (Exception exception) {
            throw ApiException.validation("timezone must be a valid IANA time zone");
        }
    }

    private record CredentialOperation(String credentialVerifier, String reason) {
    }
}
