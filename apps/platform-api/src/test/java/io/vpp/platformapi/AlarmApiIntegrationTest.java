package io.vpp.platformapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.alarm.AlarmDtos.AlarmResponse;
import io.vpp.platformapi.alarm.AlarmDtos.RuleResponse;
import io.vpp.platformapi.alarm.AlarmRepository;
import io.vpp.platformapi.alarm.AlarmService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.6-alpine:///vpp",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "platform.alarms.enabled=false"
})
class AlarmApiIntegrationTest {
    private static final UUID TENANT=UUID.fromString("7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941");
    @Autowired ServletWebServerApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AlarmRepository alarms;
    @Autowired AlarmService alarmService;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test void alarmLifecycleDedupRecoveryAuditAndTenantIsolation() throws Exception {
        Fixture f=fixture();
        JsonNode rule=send("POST","/api/v1/alarm-rules","""
          {"code":"soc-high-%s","name":"SOC过高","rule_type":"SOC_HIGH","device_type":"BATTERY",
           "metric":"soc_pct","threshold":95,"clear_threshold":92,"duration_seconds":0,"severity":"MAJOR","enabled":true}
          """.formatted(f.suffix),TENANT,"local-admin",201);
        UUID alarmId=UUID.randomUUID(); Instant now=Instant.now();
        jdbc.update("""
          INSERT INTO alarm(id,tenant_id,rule_id,object_type,object_id,state,severity,first_occurred_at,last_occurred_at,evidence_json)
          VALUES (?,?,?,'DEVICE',?,'OPEN','MAJOR',?,?,?::jsonb)
          """,alarmId,TENANT,UUID.fromString(rule.get("id").asString()),f.device,Timestamp.from(now),Timestamp.from(now),"{\"soc_pct\":96.1,\"limit_pct\":95}");
        UUID transition=UUID.randomUUID();
        jdbc.update("""
          INSERT INTO alarm_transition(id,tenant_id,alarm_id,event_type,from_state,to_state,evidence_json,occurred_at)
          VALUES (?,?,?,'AlarmOpened',NULL,'OPEN','{}'::jsonb,?)
          """,transition,TENANT,alarmId,Timestamp.from(now));

        JsonNode ack=send("POST","/api/v1/alarms/"+alarmId+"/acknowledge","{\"reason\":\"值班员已核对\"}",TENANT,"local-admin",200);
        assertThat(ack.get("state").asString()).isEqualTo("ACKNOWLEDGED");
        send("POST","/api/v1/alarms/"+alarmId+"/notes","{\"reason\":\"现场正在检查\"}",TENANT,"local-admin",200);
        send("POST","/api/v1/alarms/"+alarmId+"/close","{\"reason\":\"人工关闭并持续监控\"}",TENANT,"local-admin",200);
        JsonNode detail=send("GET","/api/v1/alarms/"+alarmId,null,TENANT,"local-admin",200);
        assertThat(detail.get("alarm").get("state").asString()).isEqualTo("CLOSED");
        assertThat(detail.get("timeline").size()).isEqualTo(4);

        UUID other=UUID.randomUUID(),user=UUID.randomUUID();
        jdbc.update("INSERT INTO tenant(id,name,status) VALUES (?,?,'ACTIVE')",other,"Other");
        jdbc.update("INSERT INTO app_user(id,tenant_id,external_subject,status) VALUES (?,?,?,'ACTIVE')",user,other,"other-admin");
        jdbc.update("INSERT INTO app_user_role(tenant_id,user_id,role) VALUES (?,?, 'TENANT_ADMIN')",other,user);
        send("GET","/api/v1/alarms/"+alarmId,null,other,"other-admin",404);

        Integer audit=jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE tenant_id=? AND object_type='ALARM' AND object_id=?",Integer.class,TENANT,alarmId.toString());
        Integer events=jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id=? AND aggregate_type='ALARM' AND aggregate_id=?",Integer.class,TENANT,alarmId.toString());
        assertThat(audit).isEqualTo(3); assertThat(events).isEqualTo(2);
        assertThatThrownBy(()->jdbc.update("UPDATE alarm_transition SET reason='tamper' WHERE id=?",transition)).hasMessageContaining("append-only");
    }

    @Test void onlyAcknowledgedOrRecoveredAlarmCanCloseAndAuditorCannotMutate() throws Exception {
        Fixture f=fixture(); UUID rule=UUID.randomUUID(),alarm=UUID.randomUUID(); Instant now=Instant.now();
        jdbc.update("INSERT INTO alarm_rule(id,tenant_id,code,name,rule_type,duration_seconds,severity,version,enabled) VALUES (?,?,?,'遥测过期','STALE',30,'MAJOR',1,true)",rule,TENANT,"stale-"+f.suffix);
        jdbc.update("INSERT INTO alarm(id,tenant_id,rule_id,object_type,object_id,state,severity,first_occurred_at,last_occurred_at) VALUES (?,?,?,'DEVICE',?,'OPEN','MAJOR',?,?)",alarm,TENANT,rule,f.device,Timestamp.from(now),Timestamp.from(now));
        JsonNode conflict=send("POST","/api/v1/alarms/"+alarm+"/close","{\"reason\":\"尚未确认\"}",TENANT,"local-admin",409);
        assertThat(conflict.get("code").asString()).isEqualTo("RESOURCE_CONFLICT");
        send("POST","/api/v1/alarms/"+alarm+"/acknowledge","{\"reason\":\"已知晓\"}",TENANT,"local-admin",200);
        send("POST","/api/v1/alarms/"+alarm+"/close","{\"reason\":\"接受风险并关闭\"}",TENANT,"local-admin",200);
    }

    @Test void evaluatorConditionOpensDeduplicatesRepeatsAndRecovers() throws Exception {
        Fixture fixture=fixture();
        JsonNode created=send("POST","/api/v1/alarm-rules","""
          {"code":"power-high-%s","name":"功率越限","rule_type":"POWER_HIGH","device_type":"BATTERY",
           "metric":"power_kw","threshold":100,"clear_threshold":95,"duration_seconds":0,"severity":"MAJOR","enabled":true}
          """.formatted(fixture.suffix),TENANT,"local-admin",201);
        RuleResponse rule=alarms.findRule(TENANT,UUID.fromString(created.get("id").asString())).orElseThrow();
        AlarmRepository.AlarmTarget target=alarms.targets(TENANT,"BATTERY").stream()
                .filter(value->value.id().equals(fixture.device)).findFirst().orElseThrow();
        Instant start=Instant.now();
        var first=mapper.createObjectNode().put("event_id","event-1").put("power_kw",105);
        AlarmResponse opened=alarmService.condition(TENANT,rule,target,true,first,start);
        assertThat(opened.state()).isEqualTo("OPEN");
        assertThat(opened.occurrenceCount()).isEqualTo(1);

        AlarmResponse duplicate=alarmService.condition(TENANT,rule,target,true,first,start.plusSeconds(1));
        assertThat(duplicate.occurrenceCount()).isEqualTo(1);
        var next=mapper.createObjectNode().put("event_id","event-2").put("power_kw",108);
        AlarmResponse repeated=alarmService.condition(TENANT,rule,target,true,next,start.plusSeconds(2));
        assertThat(repeated.occurrenceCount()).isEqualTo(2);
        AlarmResponse recovered=alarmService.condition(TENANT,rule,target,false,
                mapper.createObjectNode().put("event_id","event-3").put("power_kw",92),start.plusSeconds(3));
        assertThat(recovered.state()).isEqualTo("RECOVERED");
        AlarmResponse reopened=alarmService.condition(TENANT,rule,target,true,
                mapper.createObjectNode().put("event_id","event-4").put("power_kw",106),start.plusSeconds(4));
        assertThat(reopened.state()).isEqualTo("OPEN");
        assertThat(reopened.acknowledgedAt()).isNull();
        assertThat(reopened.acknowledgedBy()).isNull();
        assertThat(alarms.timeline(TENANT,opened.id())).extracting(value->value.eventType())
                .containsExactly("AlarmOpened","AlarmRepeated","AlarmRecovered","AlarmOpened");
    }

    private Fixture fixture() {
        String s=UUID.randomUUID().toString().substring(0,8); UUID p=UUID.randomUUID(),site=UUID.randomUUID(),model=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("INSERT INTO portfolio(id,tenant_id,name,status) VALUES (?,?,?,'ACTIVE')",p,TENANT,"P"+s);
        jdbc.update("INSERT INTO site(id,tenant_id,portfolio_id,name,timezone,grid_connection_limit_kw,status) VALUES (?,?,?,?, 'Asia/Shanghai',500,'ACTIVE')",site,TENANT,p,"S"+s);
        jdbc.update("INSERT INTO device_model(id,tenant_id,name,type,schema_version,point_schema_json,status) VALUES (?,?,?,'BATTERY',1,'{}'::jsonb,'ACTIVE')",model,TENANT,"M"+s);
        jdbc.update("INSERT INTO device(id,tenant_id,site_id,model_id,external_code,name,status) VALUES (?,?,?,?,?,?,'ACTIVE')",device,TENANT,site,model,"bess-"+s,"BESS "+s);
        return new Fixture(s,device);
    }
    private JsonNode send(String method,String path,String body,UUID tenant,String subject,int expected)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(10)).header("X-VPP-Tenant-Id",tenant.toString()).header("X-VPP-Subject",subject);
        if(body!=null)b.header("Content-Type","application/json"); b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var response=http.send(b.build(),HttpResponse.BodyHandlers.ofString()); assertThat(response.statusCode()).as(response.body()).isEqualTo(expected); return mapper.readTree(response.body());
    }
    record Fixture(String suffix,UUID device){}
}
