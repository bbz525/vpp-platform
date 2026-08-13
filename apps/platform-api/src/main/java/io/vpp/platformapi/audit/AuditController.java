package io.vpp.platformapi.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.vpp.platformapi.audit.AuditDtos.AuditEventResponse;
import io.vpp.platformapi.security.ActorResolver;
import io.vpp.platformapi.security.Role;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final ActorResolver actors;
    private final AuditService service;

    public AuditController(ActorResolver actors, AuditService service) {
        this.actors = actors;
        this.service = service;
    }

    @GetMapping
    public List<AuditEventResponse> list(
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId) {
        var actor = actors.require(Role.TENANT_ADMIN, Role.AUDITOR);
        return service.list(actor.tenantId(), objectType, objectId);
    }
}
