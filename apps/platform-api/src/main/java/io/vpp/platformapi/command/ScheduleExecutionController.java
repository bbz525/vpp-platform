package io.vpp.platformapi.command;
import java.util.UUID;import jakarta.validation.constraints.Max;import jakarta.validation.constraints.Min;import org.springframework.validation.annotation.Validated;import org.springframework.web.bind.annotation.*;import io.vpp.platformapi.security.ActorResolver;import io.vpp.platformapi.security.Role;
@Validated @RestController @RequestMapping("/api/v1/schedules")
public class ScheduleExecutionController {
 private final ActorResolver actors;private final ScheduleExecutionService service;
 ScheduleExecutionController(ActorResolver a,ScheduleExecutionService s){actors=a;service=s;}
 @GetMapping("/{id}/execution") public CommandDtos.ScheduleExecutionResponse get(@PathVariable UUID id,
         @RequestParam(defaultValue="0") @Min(0) int offset,
         @RequestParam(defaultValue="100") @Min(1) @Max(500) int limit){var a=actors.require(Role.TENANT_ADMIN,Role.OPERATOR,Role.AUDITOR,Role.SITE_ADMIN);return service.get(a.tenantId(),id,offset,limit);}
}
