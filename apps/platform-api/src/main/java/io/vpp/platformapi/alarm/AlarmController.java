package io.vpp.platformapi.alarm;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import io.vpp.platformapi.alarm.AlarmDtos.*;
import io.vpp.platformapi.security.ActorResolver;
import io.vpp.platformapi.security.Role;

@RestController
@RequestMapping("/api/v1")
public class AlarmController {
    private final ActorResolver actors; private final AlarmService service;
    public AlarmController(ActorResolver actors, AlarmService service) { this.actors=actors; this.service=service; }

    @GetMapping("/alarm-rules")
    public List<RuleResponse> rules() { return service.rules(actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.AUDITOR,Role.SITE_ADMIN).tenantId()); }
    @PostMapping("/alarm-rules") @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@Valid @RequestBody CreateRuleRequest request) { return service.createRule(actors.require(Role.TENANT_ADMIN),request); }
    @GetMapping("/alarms/coverage")
    public CoverageResponse coverage() { return service.coverage(actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.AUDITOR,Role.SITE_ADMIN).tenantId()); }
    @GetMapping("/alarms")
    public List<AlarmResponse> alarms(@RequestParam(required=false) String state,@RequestParam(required=false) String severity,
            @RequestParam(required=false) UUID portfolioId,@RequestParam(defaultValue="100") @Min(1) @Max(500) int limit) {
        return service.alarms(actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.AUDITOR,Role.SITE_ADMIN).tenantId(),state,severity,portfolioId,limit);
    }
    @GetMapping("/alarms/{id}")
    public AlarmDetailResponse detail(@PathVariable UUID id) { return service.detail(actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.AUDITOR,Role.SITE_ADMIN).tenantId(),id); }
    @PostMapping("/alarms/{id}/acknowledge")
    public AlarmResponse acknowledge(@PathVariable UUID id,@Valid @RequestBody ActionRequest request) { return service.acknowledge(actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.SITE_ADMIN),id,request); }
    @PostMapping("/alarms/{id}/notes")
    public AlarmDetailResponse note(@PathVariable UUID id,@Valid @RequestBody ActionRequest request) { return service.note(actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.SITE_ADMIN),id,request); }
    @PostMapping("/alarms/{id}/close")
    public AlarmResponse close(@PathVariable UUID id,@Valid @RequestBody ActionRequest request) { return service.close(actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.SITE_ADMIN),id,request); }
}
