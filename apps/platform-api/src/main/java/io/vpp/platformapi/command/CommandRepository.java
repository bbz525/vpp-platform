package io.vpp.platformapi.command;

import static io.vpp.platformapi.command.CommandDtos.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
class CommandRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    CommandRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    List<CommandResponse> list(UUID tenant, UUID schedule, int offset, int limit) {
        return jdbc.query("SELECT * FROM command WHERE tenant_id=? AND (?::uuid IS NULL OR schedule_id=?) ORDER BY created_at,id OFFSET ? LIMIT ?",
                this::command, tenant, schedule, schedule, offset, limit);
    }
    int count(UUID tenant, UUID schedule) {
        return jdbc.queryForObject("SELECT count(*) FROM command WHERE tenant_id=? AND (?::uuid IS NULL OR schedule_id=?)",
                Integer.class, tenant, schedule, schedule);
    }
    Optional<CommandResponse> find(UUID tenant, UUID id) {
        return jdbc.query("SELECT * FROM command WHERE tenant_id=? AND id=?", this::command, tenant,id).stream().findFirst();
    }
    Optional<CommandResponse> lock(UUID tenant, UUID id) {
        return jdbc.query("SELECT * FROM command WHERE tenant_id=? AND id=? FOR UPDATE", this::command, tenant,id).stream().findFirst();
    }
    List<CommandAttemptResponse> attempts(UUID tenant, UUID id) {
        return jdbc.query("SELECT * FROM command_attempt WHERE tenant_id=? AND command_id=? ORDER BY attempt_no",
                (rs,n)->new CommandAttemptResponse(rs.getInt("attempt_no"), instant(rs,"dispatched_at"),
                        instant(rs,"latest_ack_at"),rs.getString("latest_ack_status"),rs.getString("error_code")),tenant,id);
    }
    List<CommandEventResponse> events(UUID tenant, UUID id) {
        return jdbc.query("SELECT * FROM command_event WHERE tenant_id=? AND command_id=? ORDER BY received_at,id",
                (rs,n)->new CommandEventResponse(rs.getObject("id",UUID.class),rs.getObject("source_event_id",UUID.class),
                        rs.getString("event_type"),rs.getString("from_status"),rs.getString("reported_status"),
                        rs.getBoolean("applied"),rs.getString("reason_code"),rs.getString("message"),
                        node(rs.getString("actual_json")),instant(rs,"occurred_at"),instant(rs,"received_at")),tenant,id);
    }
    int cancelFuture(UUID tenant, UUID schedule, Instant now) {
        List<UUID> cancelled=jdbc.query("UPDATE command SET status='CANCELLED',terminal_at=?,updated_at=? WHERE tenant_id=? AND schedule_id=? AND status='CREATED' AND action='SET_POWER' RETURNING id",
                (rs,n)->rs.getObject(1,UUID.class),Timestamp.from(now),Timestamp.from(now),tenant,schedule);
        for(UUID id:cancelled)event(tenant,id,UUID.randomUUID(),"CANCEL","CREATED","CANCELLED",true,"EMERGENCY_STOP",null,null,now,now);
        return cancelled.size();
    }
    UUID insertStop(UUID tenant, CommandResponse parent, String key, String reason, Instant now, Instant expires) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO command(id,tenant_id,device_id,schedule_id,schedule_version_id,parent_command_id,idempotency_key,action,parameters_json,safety_config_version,status,not_before,expires_at) SELECT ?,tenant_id,device_id,schedule_id,schedule_version_id,id,?,'STOP',?::jsonb,safety_config_version,'CREATED',?,? FROM command WHERE tenant_id=? AND id=?",
                id,key,json(Map.of("reason",reason)),Timestamp.from(now),Timestamp.from(expires),tenant,parent.id());
        return id;
    }
    List<CommandResponse> scheduleDevices(UUID tenant,UUID schedule){return jdbc.query("SELECT DISTINCT ON (device_id) * FROM command WHERE tenant_id=? AND schedule_id=? ORDER BY device_id,created_at",this::command,tenant,schedule);}
    void cancelSchedule(UUID tenant,UUID schedule,Instant now){jdbc.update("UPDATE schedule SET status='CANCELLED',updated_at=? WHERE tenant_id=? AND id=? AND status='APPROVED'",Timestamp.from(now),tenant,schedule);}
    Map<String,Object> scheduleSnapshot(UUID tenant,UUID schedule){return jdbc.query("SELECT id,status FROM schedule WHERE tenant_id=? AND id=?",(rs,n)->Map.<String,Object>of("id",rs.getObject("id",UUID.class),"status",rs.getString("status")),tenant,schedule).stream().findFirst().orElseThrow();}
    UUID tenantId(UUID command){return tenant(command);}
    List<DispatchSeed> lockDue(Instant now,int limit){return jdbc.query("""
            SELECT c.*,d.external_code FROM command c JOIN device d ON d.tenant_id=c.tenant_id AND d.id=c.device_id
            WHERE c.status='CREATED' AND c.not_before<=? AND c.expires_at>? ORDER BY c.not_before,c.id
            FOR UPDATE OF c SKIP LOCKED LIMIT ?
            """,(rs,n)->new DispatchSeed(rs.getObject("tenant_id",UUID.class),command(rs,n),rs.getString("external_code"),rs.getLong("safety_config_version")),
            Timestamp.from(now),Timestamp.from(now),limit);}
    void dispatch(DispatchSeed seed,Instant now){
        CommandResponse c=seed.command();
        int attempt=jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM command_attempt WHERE command_id=?",Integer.class,c.id());
        jdbc.update("INSERT INTO command_attempt(id,tenant_id,command_id,attempt_no,dispatched_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(),tenant(c.id()),c.id(),attempt,Timestamp.from(now));
        Map<String,Object> payload=new java.util.LinkedHashMap<>();payload.put("schema","vpp.command.requested");payload.put("schema_version",1);
        payload.put("command_id",c.id());payload.put("idempotency_key",c.idempotencyKey());payload.put("tenant_id",tenant(c.id()));
        payload.put("device_id",seed.externalCode());payload.put("schedule_id",c.scheduleId());payload.put("schedule_version",scheduleVersion(c.scheduleVersionId()));
        payload.put("action",c.action());payload.put("parameters",c.parameters());payload.put("not_before",c.notBefore());payload.put("expires_at",c.expiresAt());
        payload.put("safety_config_version",seed.safetyConfigVersion());payload.put("created_at",c.createdAt());
        UUID event=UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,topic,message_key,payload_json) VALUES (?,?,'COMMAND',?,'vpp.command.requests.v1',?,?::jsonb)",
                event,tenant(c.id()),c.id().toString(),tenant(c.id())+":"+seed.externalCode(),json(payload));
        jdbc.update("UPDATE command SET status='DISPATCHED',updated_at=?,lock_version=lock_version+1 WHERE id=? AND status='CREATED'",Timestamp.from(now),c.id());
        event(tenant(c.id()),c.id(),event,"DISPATCH","CREATED","DISPATCHED",true,null,null,null,now,now);
    }
    List<CommandResponse> lockExpired(Instant now,int limit){return jdbc.query("SELECT * FROM command WHERE status IN ('CREATED','DISPATCHED','ACCEPTED','EXECUTING') AND expires_at<=? ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT ?",this::command,Timestamp.from(now),limit);}
    void timeout(CommandResponse c,Instant now){jdbc.update("UPDATE command SET status='TIMED_OUT',terminal_at=?,updated_at=?,last_reason_code='ACK_TIMEOUT',lock_version=lock_version+1 WHERE id=?",Timestamp.from(now),Timestamp.from(now),c.id());event(tenant(c.id()),c.id(),UUID.randomUUID(),"TIMEOUT",c.status(),"TIMED_OUT",true,"ACK_TIMEOUT",null,null,now,now);}
    boolean claim(UUID event){return jdbc.query("INSERT INTO processed_event(consumer,event_id) VALUES ('platform-command-ack',?) ON CONFLICT DO NOTHING RETURNING event_id",(rs,n)->rs.getObject(1,UUID.class),event).size()==1;}
    AckSeed lockAck(UUID commandId){return jdbc.query("SELECT c.tenant_id,c.idempotency_key,c.status,d.external_code FROM command c JOIN device d ON d.tenant_id=c.tenant_id AND d.id=c.device_id WHERE c.id=? FOR UPDATE OF c",(rs,n)->new AckSeed(rs.getObject("tenant_id",UUID.class),rs.getString("idempotency_key"),rs.getString("status"),rs.getString("external_code")),commandId).stream().findFirst().orElse(null);}
    void ack(UUID commandId,AckSeed seed,UUID eventId,String key,String reported,String reason,String message,JsonNode actual,Instant occurred,Instant received){
        boolean applied=key.equals(seed.idempotencyKey())&&canAdvance(seed.status(),reported);
        if(applied){boolean terminal=reported.equals("SUCCEEDED")||reported.equals("FAILED");jdbc.update("UPDATE command SET status=?,terminal_at=?,last_reason_code=?,last_message=?,actual_json=?::jsonb,updated_at=?,lock_version=lock_version+1 WHERE id=?",reported,terminal?Timestamp.from(received):null,reason,message,actual==null?null:actual.toString(),Timestamp.from(received),commandId);}
        event(seed.tenantId(),commandId,eventId,"ACK",seed.status(),reported,applied,reason,message,actual,occurred,received);
        jdbc.update("UPDATE command_attempt SET latest_ack_at=?,latest_ack_status=? WHERE command_id=? AND attempt_no=(SELECT MAX(attempt_no) FROM command_attempt WHERE command_id=?)",Timestamp.from(received),reported,commandId,commandId);
    }
    private boolean canAdvance(String current,String next){if(List.of("SUCCEEDED","FAILED","TIMED_OUT","CANCELLED").contains(current))return false;int c=rank(current),n=rank(next);return n>c||List.of("SUCCEEDED","FAILED").contains(next);}
    private int rank(String s){return switch(s){case "CREATED"->0;case "DISPATCHED"->1;case "ACCEPTED"->2;case "EXECUTING"->3;case "SUCCEEDED","FAILED","TIMED_OUT","CANCELLED"->4;default->-1;};}
    private void event(UUID tenant,UUID command,UUID source,String type,String from,String reported,boolean applied,String reason,String message,JsonNode actual,Instant occurred,Instant received){jdbc.update("INSERT INTO command_event(id,tenant_id,command_id,source_event_id,event_type,from_status,reported_status,applied,reason_code,message,actual_json,occurred_at,received_at) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?) ON CONFLICT (tenant_id,source_event_id) DO NOTHING",UUID.randomUUID(),tenant,command,source,type,from,reported,applied,reason,message,actual==null?null:actual.toString(),Timestamp.from(occurred),Timestamp.from(received));}
    private UUID tenant(UUID command){return jdbc.queryForObject("SELECT tenant_id FROM command WHERE id=?",UUID.class,command);}
    private Integer scheduleVersion(UUID version){return version==null?null:jdbc.queryForObject("SELECT version FROM schedule_version WHERE id=?",Integer.class,version);}
    private CommandResponse command(ResultSet rs,int n)throws SQLException { return new CommandResponse(
            rs.getObject("id",UUID.class),rs.getObject("device_id",UUID.class),rs.getObject("schedule_id",UUID.class),
            rs.getObject("schedule_version_id",UUID.class),rs.getObject("parent_command_id",UUID.class),
            rs.getString("idempotency_key"),rs.getString("action"),node(rs.getString("parameters_json")),rs.getString("status"),
            instant(rs,"not_before"),instant(rs,"expires_at"),instant(rs,"terminal_at"),rs.getString("last_reason_code"),
            rs.getString("last_message"),node(rs.getString("actual_json")),instant(rs,"created_at"),instant(rs,"updated_at")); }
    static Instant instant(ResultSet rs,String c)throws SQLException { Timestamp t=rs.getTimestamp(c);return t==null?null:t.toInstant(); }
    JsonNode node(String s){try{return s==null?null:mapper.readTree(s);}catch(JacksonException e){throw new IllegalStateException(e);}}
    String json(Object o){try{return mapper.writeValueAsString(o);}catch(JacksonException e){throw new IllegalStateException(e);}}
    record DispatchSeed(UUID tenantId,CommandResponse command,String externalCode,long safetyConfigVersion){}
    record AckSeed(UUID tenantId,String idempotencyKey,String status,String externalCode){}
}
