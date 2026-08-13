package io.vpp.platformapi.command;

import static io.vpp.platformapi.command.CommandDtos.*;
import java.util.List;import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;
import io.vpp.platformapi.audit.AuditService;import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.schedule.ScheduleDtos.ScheduleDecisionResponse;

@Service
class ScheduleExecutionService {
    private final JdbcTemplate jdbc;private final CommandService commands;private final AuditService audits;
    ScheduleExecutionService(JdbcTemplate j,CommandService c,AuditService a){jdbc=j;commands=c;audits=a;}
    ScheduleExecutionResponse get(UUID tenant,UUID schedule,int offset,int limit){
        if(Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM schedule WHERE tenant_id=? AND id=?)",Boolean.class,tenant,schedule)))throw ApiException.notFound("schedule");
        ScheduleDecisionResponse decision=jdbc.query("SELECT sa.*,(SELECT count(*) FROM command c WHERE c.tenant_id=sa.tenant_id AND c.schedule_id=sa.schedule_id) command_count FROM schedule_approval sa WHERE tenant_id=? AND schedule_id=?",(rs,n)->new ScheduleDecisionResponse(rs.getObject("id",UUID.class),schedule,rs.getObject("schedule_version_id",UUID.class),rs.getInt("schedule_version"),rs.getString("decision"),rs.getString("reason"),rs.getString("actor_id"),rs.getTimestamp("decided_at").toInstant(),rs.getInt("command_count")),tenant,schedule).stream().findFirst().orElse(null);
        int total=commands.count(tenant,schedule);
        List<CommandDetailResponse> details=commands.list(tenant,schedule,offset,limit).stream().map(c->commands.get(tenant,c.id())).toList();
        var audit=audits.list(tenant,"SCHEDULE",schedule.toString());
        java.util.ArrayList<io.vpp.platformapi.audit.AuditDtos.AuditEventResponse> all=new java.util.ArrayList<>(audit);
        for(var c:details)all.addAll(audits.list(tenant,"COMMAND",c.command().id().toString()));
        all.sort(java.util.Comparator.comparing(io.vpp.platformapi.audit.AuditDtos.AuditEventResponse::occurredAt));
        return new ScheduleExecutionResponse(schedule,total,offset,limit,decision,details,List.copyOf(all));
    }
}
