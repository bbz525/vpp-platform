package io.vpp.platformapi.alarm;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.alarm.AlarmDtos.AlarmResponse;
import io.vpp.platformapi.alarm.AlarmDtos.CreateRuleRequest;
import io.vpp.platformapi.alarm.AlarmDtos.RuleResponse;
import io.vpp.platformapi.alarm.AlarmDtos.TransitionResponse;

@Repository
public class AlarmRepository {
    private static final List<String> SUPPORTED = List.of(
            "OFFLINE", "STALE", "SOC_LOW", "SOC_HIGH", "POWER_LOW", "POWER_HIGH");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AlarmRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public RuleResponse insertRule(UUID tenantId, UUID id, CreateRuleRequest request) {
        Integer version = jdbc.queryForObject(
                "SELECT coalesce(max(version),0)+1 FROM alarm_rule WHERE tenant_id=? AND code=?",
                Integer.class, tenantId, request.code());
        jdbc.update("""
                INSERT INTO alarm_rule
                  (id,tenant_id,code,name,rule_type,device_type,metric,threshold,clear_threshold,
                   duration_seconds,severity,version,enabled)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, tenantId, request.code(), request.name().trim(), request.ruleType(),
                request.deviceType(), request.metric(), request.threshold(), request.clearThreshold(),
                request.durationSeconds(), request.severity(), version, request.enabled());
        return findRule(tenantId, id).orElseThrow();
    }

    public Optional<RuleResponse> findRule(UUID tenantId, UUID id) {
        return first(jdbc.query(ruleSelect() + " AND id=?", this::mapRule, tenantId, id));
    }

    public List<RuleResponse> listRules(UUID tenantId) {
        return jdbc.query(ruleSelect() + " ORDER BY code,version DESC", this::mapRule, tenantId);
    }

    public List<TenantRule> enabledRules() {
        return jdbc.query(ruleSelectAll() + " WHERE enabled=true ORDER BY tenant_id,code,version DESC",
                (rs, row) -> new TenantRule(rs.getObject("tenant_id", UUID.class), mapRule(rs, row)));
    }

    public List<AlarmTarget> targets(UUID tenantId, String deviceType) {
        String typeClause = deviceType == null ? "" : " AND m.type=?";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (deviceType != null) args.add(deviceType);
        return jdbc.query("""
                SELECT d.id,d.external_code,d.name,d.status,m.type,s.id site_id,s.portfolio_id
                FROM device d JOIN device_model m ON m.tenant_id=d.tenant_id AND m.id=d.model_id
                JOIN site s ON s.tenant_id=d.tenant_id AND s.id=d.site_id
                WHERE d.tenant_id=? AND d.status='ACTIVE'
                """ + typeClause + " ORDER BY d.id", (rs, row) -> new AlarmTarget(
                rs.getObject("id", UUID.class), rs.getString("external_code"), rs.getString("name"),
                rs.getString("status"), rs.getString("type"), rs.getObject("site_id", UUID.class),
                rs.getObject("portfolio_id", UUID.class)), args.toArray());
    }

    public List<AlarmResponse> listAlarms(UUID tenantId, String state, String severity,
            UUID portfolioId, int limit) {
        StringBuilder sql = new StringBuilder(alarmSelect() + " WHERE a.tenant_id=?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (state != null) { sql.append(" AND a.state=?"); args.add(state); }
        if (severity != null) { sql.append(" AND a.severity=?"); args.add(severity); }
        if (portfolioId != null) { sql.append(" AND s.portfolio_id=?"); args.add(portfolioId); }
        sql.append(" ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 1 WHEN 'MAJOR' THEN 2 WHEN 'WARNING' THEN 3 ELSE 4 END,a.last_occurred_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::mapAlarm, args.toArray());
    }

    public Optional<AlarmResponse> findAlarm(UUID tenantId, UUID id) {
        return first(jdbc.query(alarmSelect() + " WHERE a.tenant_id=? AND a.id=?",
                this::mapAlarm, tenantId, id));
    }

    public Optional<AlarmResponse> findActiveForUpdate(UUID tenantId, UUID ruleId, UUID objectId) {
        return first(jdbc.query(alarmSelect() + " WHERE a.tenant_id=? AND a.rule_id=? AND a.object_id=? AND a.state<>'CLOSED' FOR UPDATE OF a",
                this::mapAlarm, tenantId, ruleId, objectId));
    }

    public Optional<AlarmResponse> findActive(UUID tenantId, UUID ruleId, UUID objectId) {
        return first(jdbc.query(alarmSelect() + " WHERE a.tenant_id=? AND a.rule_id=? AND a.object_id=? AND a.state<>'CLOSED'",
                this::mapAlarm, tenantId, ruleId, objectId));
    }

    public List<TransitionResponse> timeline(UUID tenantId, UUID alarmId) {
        return jdbc.query("""
                SELECT id,event_type,from_state,to_state,actor_id,reason,evidence_json::text,occurred_at
                FROM alarm_transition WHERE tenant_id=? AND alarm_id=? ORDER BY occurred_at,id
                """, (rs, row) -> new TransitionResponse(rs.getObject("id", UUID.class),
                rs.getString("event_type"), rs.getString("from_state"), rs.getString("to_state"),
                rs.getString("actor_id"), rs.getString("reason"), json(rs.getString("evidence_json")),
                rs.getTimestamp("occurred_at").toInstant()), tenantId, alarmId);
    }

    public AlarmResponse open(UUID tenantId, RuleResponse rule, AlarmTarget target,
            Instant occurredAt, JsonNode evidence) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alarm (id,tenant_id,rule_id,object_type,object_id,state,severity,
                  first_occurred_at,last_occurred_at,evidence_json)
                VALUES (?,?,?,'DEVICE',?,'OPEN',?,?,?,?::jsonb)
                """, id, tenantId, rule.id(), target.id(), rule.severity(), Timestamp.from(occurredAt),
                Timestamp.from(occurredAt), json(evidence));
        transition(tenantId, id, "AlarmOpened", null, "OPEN", null, null, evidence, occurredAt);
        return findAlarm(tenantId, id).orElseThrow();
    }

    public AlarmResponse repeat(UUID tenantId, AlarmResponse current, Instant occurredAt, JsonNode evidence) {
        jdbc.update("""
                UPDATE alarm SET last_occurred_at=?,occurrence_count=occurrence_count+1,
                  evidence_json=?::jsonb,updated_at=now(),state=CASE WHEN state='RECOVERED' THEN 'OPEN' ELSE state END,
                  recovered_at=CASE WHEN state='RECOVERED' THEN NULL ELSE recovered_at END,
                  acknowledged_at=CASE WHEN state='RECOVERED' THEN NULL ELSE acknowledged_at END,
                  acknowledged_by=CASE WHEN state='RECOVERED' THEN NULL ELSE acknowledged_by END
                WHERE tenant_id=? AND id=?
                """, Timestamp.from(occurredAt), json(evidence), tenantId, current.id());
        String event = "RECOVERED".equals(current.state()) ? "AlarmOpened" : "AlarmRepeated";
        String to = "RECOVERED".equals(current.state()) ? "OPEN" : current.state();
        transition(tenantId, current.id(), event, current.state(), to, null, null,
                evidence, occurredAt);
        return findAlarm(tenantId, current.id()).orElseThrow();
    }

    public AlarmResponse recover(UUID tenantId, AlarmResponse current, Instant at, JsonNode evidence) {
        jdbc.update("UPDATE alarm SET state='RECOVERED',recovered_at=?,evidence_json=?::jsonb,updated_at=now() WHERE tenant_id=? AND id=?",
                Timestamp.from(at), json(evidence), tenantId, current.id());
        transition(tenantId, current.id(), "AlarmRecovered", current.state(), "RECOVERED", null,
                null, evidence, at);
        return findAlarm(tenantId, current.id()).orElseThrow();
    }

    public AlarmResponse acknowledge(UUID tenantId, AlarmResponse current, String actor, String reason,
            Instant at) {
        String next = "OPEN".equals(current.state()) ? "ACKNOWLEDGED" : current.state();
        jdbc.update("UPDATE alarm SET state=?,acknowledged_at=?,acknowledged_by=?,updated_at=now() WHERE tenant_id=? AND id=?",
                next, Timestamp.from(at), actor, tenantId, current.id());
        transition(tenantId, current.id(), "AlarmAcknowledged", current.state(), next, actor, reason,
                current.evidence(), at);
        return findAlarm(tenantId, current.id()).orElseThrow();
    }

    public void note(UUID tenantId, AlarmResponse current, String actor, String reason, Instant at) {
        transition(tenantId, current.id(), "AlarmNoted", current.state(), current.state(), actor,
                reason, current.evidence(), at);
    }

    public AlarmResponse close(UUID tenantId, AlarmResponse current, String actor, String reason, Instant at) {
        jdbc.update("UPDATE alarm SET state='CLOSED',closed_at=?,updated_at=now() WHERE tenant_id=? AND id=?",
                Timestamp.from(at), tenantId, current.id());
        transition(tenantId, current.id(), "AlarmClosed", current.state(), "CLOSED", actor, reason,
                current.evidence(), at);
        return findAlarm(tenantId, current.id()).orElseThrow();
    }

    public void outbox(UUID tenantId, AlarmResponse alarm, String eventType, String fromState,
            String actor, String reason, Instant at) {
        var payload = mapper.createObjectNode();
        payload.put("schema_version", 1); payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", eventType); payload.put("tenant_id", tenantId.toString());
        payload.put("alarm_id", alarm.id().toString()); payload.put("rule_id", alarm.ruleId().toString());
        payload.put("object_type", alarm.objectType()); payload.put("object_id", alarm.objectId().toString());
        payload.put("severity", alarm.severity());
        if (fromState == null) payload.putNull("from_state"); else payload.put("from_state", fromState);
        payload.put("to_state", alarm.state()); payload.put("occurred_at", at.toString());
        payload.put("occurrence_count", alarm.occurrenceCount());
        if (actor == null) payload.putNull("actor_id"); else payload.put("actor_id", actor);
        if (reason == null) payload.putNull("reason"); else payload.put("reason", reason);
        payload.set("evidence", alarm.evidence());
        jdbc.update("""
                INSERT INTO outbox_event (id,tenant_id,aggregate_type,aggregate_id,topic,message_key,payload_json)
                VALUES (?,?,'ALARM',?,'vpp.alarm.events.v1',?,?::jsonb)
                """, UUID.randomUUID(), tenantId, alarm.id().toString(),
                tenantId + ":" + alarm.id(), json(payload));
    }

    private void transition(UUID tenantId, UUID alarmId, String eventType, String from, String to,
            String actor, String reason, JsonNode evidence, Instant at) {
        jdbc.update("""
                INSERT INTO alarm_transition
                  (id,tenant_id,alarm_id,event_type,from_state,to_state,actor_id,reason,evidence_json,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)
                """, UUID.randomUUID(), tenantId, alarmId, eventType, from, to, actor, reason,
                json(evidence), Timestamp.from(at));
    }

    private RuleResponse mapRule(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String type = rs.getString("rule_type");
        return new RuleResponse(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                type, rs.getString("device_type"), rs.getString("metric"), rs.getBigDecimal("threshold"),
                rs.getBigDecimal("clear_threshold"), rs.getInt("duration_seconds"), rs.getString("severity"),
                rs.getInt("version"), rs.getBoolean("enabled"), SUPPORTED.contains(type) ? "ACTIVE" : "UNSUPPORTED",
                rs.getTimestamp("created_at").toInstant());
    }

    private AlarmResponse mapAlarm(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AlarmResponse(rs.getObject("id", UUID.class), rs.getObject("rule_id", UUID.class),
                rs.getString("rule_code"), rs.getString("rule_name"), rs.getString("object_type"),
                rs.getObject("object_id", UUID.class), rs.getString("object_name"), rs.getString("external_code"),
                rs.getObject("portfolio_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("state"), rs.getString("severity"), rs.getTimestamp("first_occurred_at").toInstant(),
                rs.getTimestamp("last_occurred_at").toInstant(), rs.getLong("occurrence_count"),
                instant(rs, "acknowledged_at"), rs.getString("acknowledged_by"), instant(rs, "recovered_at"),
                instant(rs, "closed_at"), json(rs.getString("evidence_json")));
    }

    private static String ruleSelect() { return ruleSelectAll() + " WHERE tenant_id=?"; }
    private static String ruleSelectAll() { return "SELECT id,tenant_id,code,name,rule_type,device_type,metric,threshold,clear_threshold,duration_seconds,severity,version,enabled,created_at FROM alarm_rule"; }
    private static String alarmSelect() { return """
            SELECT a.*,r.code rule_code,r.name rule_name,d.name object_name,d.external_code,
                   d.site_id,s.portfolio_id
            FROM alarm a JOIN alarm_rule r ON r.id=a.rule_id
            JOIN device d ON d.tenant_id=a.tenant_id AND d.id=a.object_id
            JOIN site s ON s.tenant_id=d.tenant_id AND s.id=d.site_id
            """; }
    private static Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private JsonNode json(String value) {
        try { return mapper.readTree(value); } catch (JacksonException exception) { throw new IllegalStateException("invalid alarm JSON", exception); }
    }
    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); } catch (JacksonException exception) { throw new IllegalStateException("cannot serialize alarm JSON", exception); }
    }
    private static <T> Optional<T> first(List<T> values) { return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst()); }

    public record AlarmTarget(UUID id, String externalCode, String name, String status,
            String deviceType, UUID siteId, UUID portfolioId) {}
    public record TenantRule(UUID tenantId, RuleResponse rule) {}
}
