package io.vpp.platformapi.command;

import static io.vpp.platformapi.command.CommandDtos.*;
import java.util.List;import java.util.UUID;
import jakarta.validation.Valid;import jakarta.validation.constraints.Max;import jakarta.validation.constraints.Min;import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;import org.springframework.web.bind.annotation.*;
import io.vpp.platformapi.security.ActorResolver;import io.vpp.platformapi.security.Role;

@Validated @RestController @RequestMapping("/api/v1/commands")
public class CommandController {
    private final ActorResolver actors;private final CommandService service;
    CommandController(ActorResolver actors,CommandService service){this.actors=actors;this.service=service;}
    @GetMapping public List<CommandResponse> list(@RequestParam(name="schedule_id",required=false) UUID schedule,
            @RequestParam(defaultValue="0") @Min(0) int offset,
            @RequestParam(defaultValue="100") @Min(1) @Max(500) int limit){var a=actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.AUDITOR,Role.SITE_ADMIN);return service.list(a.tenantId(),schedule,offset,limit);}
    @GetMapping("/{id}") public CommandDetailResponse get(@PathVariable UUID id){var a=actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.AUDITOR,Role.SITE_ADMIN);return service.get(a.tenantId(),id);}
    @PostMapping("/{id}/stop") public CommandDetailResponse stop(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Size(min=8,max=200) String key,@Valid @RequestBody StopCommandRequest request){return service.stop(actors.require(Role.TENANT_ADMIN,Role.OPERATOR),id,key,request);}
}
