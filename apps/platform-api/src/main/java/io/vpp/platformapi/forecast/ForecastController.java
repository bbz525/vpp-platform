package io.vpp.platformapi.forecast;

import static io.vpp.platformapi.forecast.ForecastDtos.*;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import io.vpp.platformapi.security.ActorResolver;
import io.vpp.platformapi.security.Role;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ForecastController {
    private final ActorResolver actors;
    private final ForecastService service;

    public ForecastController(ActorResolver actors, ForecastService service) {
        this.actors = actors;
        this.service = service;
    }

    @PostMapping("/forecast-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody CreateRunRequest request) {
        return service.create(actors.require(Role.TENANT_ADMIN, Role.OPERATOR), key, request);
    }

    @GetMapping("/forecast-runs")
    public List<RunResponse> runs(@RequestParam(required = false) UUID targetId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.listRuns(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId(), targetId, limit);
    }

    @GetMapping("/forecast-runs/{id}")
    public RunDetailResponse detail(@PathVariable UUID id) {
        return service.detail(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId(), id);
    }

    @GetMapping("/forecasts/{id}")
    public VersionResponse version(@PathVariable UUID id) {
        return service.version(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId(), id);
    }

    @PostMapping("/forecasts/{id}/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse override(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody OverrideRequest request) {
        return service.override(actors.require(Role.TENANT_ADMIN, Role.OPERATOR), id, key, request);
    }
}
