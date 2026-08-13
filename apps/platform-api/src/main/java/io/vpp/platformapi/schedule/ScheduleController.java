package io.vpp.platformapi.schedule;

import static io.vpp.platformapi.schedule.ScheduleDtos.*;

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
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    private final ActorResolver actors;
    private final ScheduleService service;

    public ScheduleController(ActorResolver actors, ScheduleService service) {
        this.actors = actors; this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScheduleDetailResponse create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 200) String key,
            @Valid @RequestBody CreateScheduleRequest request) {
        return service.create(actors.require(Role.TENANT_ADMIN, Role.OPERATOR), key, request);
    }

    @GetMapping
    public List<ScheduleResponse> list(@RequestParam(required = false) UUID portfolioId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.list(actors.require(Role.TENANT_ADMIN, Role.OPERATOR, Role.AUDITOR,
                Role.SITE_ADMIN).tenantId(), portfolioId, limit);
    }

    @GetMapping("/{id}")
    public ScheduleDetailResponse get(@PathVariable UUID id) {
        return service.get(actors.require(Role.TENANT_ADMIN, Role.OPERATOR, Role.AUDITOR,
                Role.SITE_ADMIN).tenantId(), id);
    }
}
