package io.vpp.platformapi.command;

import static io.vpp.platformapi.command.CommandDtos.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vpp.platformapi.audit.AuditOutboxWriter;
import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.common.IdempotencyService;
import io.vpp.platformapi.config.CommandProperties;
import io.vpp.platformapi.security.ActorPrincipal;

@Service
public class CommandService {
    private final CommandRepository repository; private final CommandProperties properties;
    private final IdempotencyService idempotency; private final AuditOutboxWriter audit;
    CommandService(CommandRepository repository, CommandProperties properties,
            IdempotencyService idempotency, AuditOutboxWriter audit) {
        this.repository=repository;this.properties=properties;this.idempotency=idempotency;this.audit=audit;
    }
    public List<CommandResponse> list(UUID tenant, UUID schedule, int offset, int limit){return repository.list(tenant,schedule,offset,limit);}
    public int count(UUID tenant, UUID schedule){return repository.count(tenant,schedule);}
    public CommandDetailResponse get(UUID tenant,UUID id){
        var c=repository.find(tenant,id).orElseThrow(()->ApiException.notFound("command"));
        return new CommandDetailResponse(c,repository.attempts(tenant,id),repository.events(tenant,id));
    }
    @Transactional
    public CommandDetailResponse stop(ActorPrincipal actor, UUID id, String key, StopCommandRequest request){
        var replay=idempotency.replay(actor.tenantId(),"STOP_COMMAND",key,
                java.util.Map.of("command_id",id,"request",request));
        if(replay.isPresent()) return get(actor.tenantId(),replay.get());
        CommandResponse parent=repository.lock(actor.tenantId(),id).orElseThrow(()->ApiException.notFound("command"));
        if("STOP".equals(parent.action()) || List.of("SUCCEEDED","FAILED","TIMED_OUT","CANCELLED").contains(parent.status()))
            throw ApiException.conflict("only a non-terminal control command can be stopped");
        Instant now=Instant.now();
        java.util.Map<String,Object> scheduleBefore=parent.scheduleId()==null?null:repository.scheduleSnapshot(actor.tenantId(),parent.scheduleId());
        int cancelled=parent.scheduleId()!=null?repository.cancelFuture(actor.tenantId(),parent.scheduleId(),now):0;
        List<CommandResponse> devices=parent.scheduleId()==null?List.of(parent):repository.scheduleDevices(actor.tenantId(),parent.scheduleId());
        UUID stop=null;int index=0;
        for(CommandResponse device:devices){UUID created=repository.insertStop(actor.tenantId(),device,"stop:"+parent.scheduleId()+":"+device.deviceId()+":"+key,request.reason(),now,now.plus(properties.stopTimeout()));if(stop==null||device.deviceId().equals(parent.deviceId()))stop=created;audit.succeeded(actor,"COMMAND_STOP_REQUESTED","COMMAND",created,request.reason(),device,get(actor.tenantId(),created));index++;}
        if(parent.scheduleId()!=null)repository.cancelSchedule(actor.tenantId(),parent.scheduleId(),now);
        idempotency.remember(actor.tenantId(),"STOP_COMMAND",key,
                java.util.Map.of("command_id",id,"request",request),stop);
        if(parent.scheduleId()!=null)audit.succeeded(actor,"SCHEDULE_EMERGENCY_STOPPED","SCHEDULE",parent.scheduleId(),request.reason(),scheduleBefore,repository.scheduleSnapshot(actor.tenantId(),parent.scheduleId()));
        return get(actor.tenantId(),stop);
    }
}
