package io.vpp.platformapi.resource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.vpp.platformapi.resource.ResourceDtos.CreateTariffPlanRequest;
import io.vpp.platformapi.resource.ResourceDtos.TariffPlanResponse;
import io.vpp.platformapi.security.ActorResolver;
import io.vpp.platformapi.security.Role;

@Validated
@RestController
@RequestMapping("/api/v1/tariff-plans")
public class TariffController {
    private final ActorResolver actors;
    private final TariffService service;

    public TariffController(ActorResolver actors, TariffService service) {
        this.actors = actors;
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TariffPlanResponse create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody CreateTariffPlanRequest request) {
        return service.create(actors.require(Role.TENANT_ADMIN), key, request);
    }

    @GetMapping
    public java.util.List<TariffPlanResponse> list() {
        return service.list(actors.require(Role.TENANT_ADMIN, Role.OPERATOR, Role.AUDITOR,
                Role.SITE_ADMIN).tenantId());
    }
}
