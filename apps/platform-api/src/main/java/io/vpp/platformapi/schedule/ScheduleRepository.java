package io.vpp.platformapi.schedule;

import static io.vpp.platformapi.schedule.ScheduleDtos.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.LinkedHashMap;
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
public class ScheduleRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ScheduleRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    List<SiteConfig> sites(UUID tenantId, UUID portfolioId) {
        return jdbc.query("""
                SELECT id,timezone,grid_connection_limit_kw,status FROM site
                WHERE tenant_id=? AND portfolio_id=? ORDER BY id
                """, (rs, row) -> new SiteConfig(rs.getObject("id", UUID.class), rs.getString("timezone"),
                rs.getBigDecimal("grid_connection_limit_kw"), rs.getString("status")), tenantId, portfolioId);
    }

    List<BatteryConfig> batteries(UUID tenantId, UUID portfolioId) {
        List<BatteryConfig> batteries = jdbc.query("""
                SELECT d.id,d.site_id,d.config_version,d.status
                FROM device d JOIN site s ON s.tenant_id=d.tenant_id AND s.id=d.site_id
                JOIN device_model m ON m.tenant_id=d.tenant_id AND m.id=d.model_id
                WHERE d.tenant_id=? AND s.portfolio_id=? AND m.type='BATTERY' AND d.status='ACTIVE'
                ORDER BY d.id
                """, (rs, row) -> new BatteryConfig(rs.getObject("id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getLong("config_version"), new LinkedHashMap<>()),
                tenantId, portfolioId);
        Map<UUID, BatteryConfig> byId = new LinkedHashMap<>();
        batteries.forEach(battery -> byId.put(battery.id(), battery));
        if (byId.isEmpty()) return batteries;
        jdbc.query("""
                SELECT dc.device_id,dc.capability,dc.unit,dc.min_value,dc.max_value,dc.fallback_value,
                       dc.config_version
                FROM device_capability dc JOIN device d ON d.tenant_id=dc.tenant_id AND d.id=dc.device_id
                JOIN site s ON s.tenant_id=d.tenant_id AND s.id=d.site_id
                JOIN device_model m ON m.tenant_id=d.tenant_id AND m.id=d.model_id
                WHERE dc.tenant_id=? AND s.portfolio_id=? AND m.type='BATTERY' AND d.status='ACTIVE'
                ORDER BY dc.device_id,dc.capability
                """, rs -> {
                    UUID id = rs.getObject("device_id", UUID.class);
                    BatteryConfig battery = byId.get(id);
                    if (battery != null) battery.capabilities().put(rs.getString("capability"),
                            new Capability(rs.getString("capability"), rs.getString("unit"),
                                    rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"),
                                    rs.getBigDecimal("fallback_value"), rs.getLong("config_version")));
                }, tenantId, portfolioId);
        return batteries;
    }

    Optional<TariffConfig> tariff(UUID tenantId, UUID tariffId) {
        List<TariffConfig> rows = jdbc.query("""
                SELECT id,timezone,valid_from,valid_to,currency,version FROM tariff_plan
                WHERE tenant_id=? AND id=?
                """, (rs, row) -> new TariffConfig(rs.getObject("id", UUID.class), rs.getString("timezone"),
                rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to").toInstant(),
                rs.getString("currency"), rs.getInt("version"), tariffIntervals(tenantId, tariffId)),
                tenantId, tariffId);
        return rows.stream().findFirst();
    }

    private List<TariffPrice> tariffIntervals(UUID tenantId, UUID tariffId) {
        return jdbc.query("""
                SELECT interval_start,interval_end,price_per_kwh FROM tariff_interval
                WHERE tenant_id=? AND tariff_plan_id=? ORDER BY interval_start
                """, (rs, row) -> new TariffPrice(rs.getTimestamp("interval_start").toInstant(),
                rs.getTimestamp("interval_end").toInstant(), rs.getBigDecimal("price_per_kwh")),
                tenantId, tariffId);
    }

    void insertSnapshot(UUID id, UUID tenantId, UUID portfolioId, JsonNode content, String hash) {
        jdbc.update("INSERT INTO device_config_snapshot(id,tenant_id,portfolio_id,content_json,content_sha256) VALUES (?,?,?,?::jsonb,?)",
                id, tenantId, portfolioId, json(content), hash);
    }

    void insertSchedule(UUID id, UUID tenantId, CreateScheduleRequest request, String timezone,
            String status, String feasibility, Integer currentVersion, String failureCode,
            List<String> reasons, String actor) {
        jdbc.update("""
                INSERT INTO schedule(id,tenant_id,portfolio_id,schedule_date,timezone,status,feasibility,
                  current_version,failure_code,reasons_json,input_json,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)
                """, id, tenantId, request.portfolioId(), request.scheduleDate(), timezone, status,
                feasibility, currentVersion, failureCode, json(reasons), json(request), actor);
    }

    void insertVersion(UUID id, UUID tenantId, UUID scheduleId, UUID snapshotId,
            CreateScheduleRequest request, OptimizationModels.Result result, String hash, String actor) {
        JsonNode summary = mapper.valueToTree(result.summary());
        jdbc.update("""
                INSERT INTO schedule_version(id,tenant_id,schedule_id,version,load_forecast_version_id,
                  pv_forecast_version_id,tariff_plan_id,device_config_snapshot_id,algorithm_name,
                  algorithm_version,objective_value,summary_json,content_sha256,created_by)
                VALUES (?,?,?,1,?,?,?,?,?,?,?,?::jsonb,?,?)
                """, id, tenantId, scheduleId, request.loadForecastVersionId(), request.pvForecastVersionId(),
                request.tariffPlanId(), snapshotId, RuleBaselineOptimizer.ALGORITHM_NAME,
                RuleBaselineOptimizer.ALGORITHM_VERSION, result.summary().objectiveCost(), json(summary), hash, actor);
        for (OptimizationModels.IntervalPlan interval : result.intervals()) {
            jdbc.update("""
                    INSERT INTO schedule_interval(id,tenant_id,schedule_version_id,interval_start,interval_end,
                      load_kw,pv_kw,price_per_kwh,baseline_grid_kw,planned_grid_kw,baseline_cost,planned_cost)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), tenantId, id, Timestamp.from(interval.start()),
                    Timestamp.from(interval.end()), interval.loadKw(), interval.pvKw(), interval.pricePerKwh(),
                    interval.baselineGridKw(), interval.plannedGridKw(), interval.baselineCost(), interval.plannedCost());
        }
        for (OptimizationModels.Target target : result.targets()) {
            jdbc.update("""
                    INSERT INTO schedule_target(id,tenant_id,schedule_version_id,device_id,interval_start,
                      interval_end,charge_kw,discharge_kw,setpoint_kw,soc_start_pct,soc_end_pct)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), tenantId, id, target.deviceId(), Timestamp.from(target.start()),
                    Timestamp.from(target.end()), target.chargeKw(), target.dischargeKw(), target.setpointKw(),
                    target.socStartPct(), target.socEndPct());
        }
    }

    Optional<ScheduleDetailResponse> find(UUID tenantId, UUID id) {
        Optional<ScheduleResponse> schedule = jdbc.query("SELECT * FROM schedule WHERE tenant_id=? AND id=?",
                this::schedule, tenantId, id).stream().findFirst();
        if (schedule.isEmpty()) return Optional.empty();
        ScheduleVersionResponse version = schedule.get().currentVersion() == null ? null
                : version(tenantId, id, schedule.get().currentVersion()).orElse(null);
        return Optional.of(new ScheduleDetailResponse(schedule.get(), version, findDecisionForSchedule(tenantId,id).orElse(null)));
    }

    List<ScheduleResponse> list(UUID tenantId, UUID portfolioId, int limit) {
        return jdbc.query("""
                SELECT * FROM schedule WHERE tenant_id=? AND (?::uuid IS NULL OR portfolio_id=?)
                ORDER BY created_at DESC LIMIT ?
                """, this::schedule, tenantId, portfolioId, portfolioId, limit);
    }

    void outbox(UUID tenantId, ScheduleDetailResponse detail) {
        ScheduleResponse schedule = detail.schedule();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1); payload.put("event_id", UUID.randomUUID());
        payload.put("event_type", "SCHEDULE_GENERATED"); payload.put("tenant_id", tenantId);
        payload.put("schedule_id", schedule.id()); payload.put("portfolio_id", schedule.portfolioId());
        payload.put("schedule_date", schedule.scheduleDate()); payload.put("status", schedule.status());
        payload.put("feasibility", schedule.feasibility()); payload.put("occurred_at", Instant.now());
        if (detail.version() != null) payload.put("schedule_version_id", detail.version().id());
        jdbc.update("""
                INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,topic,message_key,payload_json)
                VALUES (?,?,'SCHEDULE',?,'vpp.schedule.events.v1',?,?::jsonb)
                """, UUID.randomUUID(), tenantId, schedule.id().toString(), tenantId + ":" + schedule.id(),
                json(payload));
    }

    Optional<DecisionContext> lockDecision(UUID tenantId, UUID scheduleId) {
        var locked = jdbc.query("SELECT id FROM schedule WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, scheduleId);
        if (locked.isEmpty()) return Optional.empty();
        return jdbc.query("""
                SELECT s.id,s.portfolio_id,s.status,s.feasibility,s.current_version,
                       sv.id AS version_id,sv.version,sv.load_forecast_version_id,
                       sv.pv_forecast_version_id,sv.tariff_plan_id,sv.device_config_snapshot_id,
                       sv.algorithm_version,sv.content_sha256,
                       (SELECT MIN(st.interval_start) FROM schedule_target st
                        WHERE st.tenant_id=sv.tenant_id AND st.schedule_version_id=sv.id) AS interval_start,
                       (SELECT MAX(st.interval_end) FROM schedule_target st
                        WHERE st.tenant_id=sv.tenant_id AND st.schedule_version_id=sv.id) AS interval_end
                FROM schedule s LEFT JOIN schedule_version sv ON sv.tenant_id=s.tenant_id
                    AND sv.schedule_id=s.id AND sv.version=s.current_version
                WHERE s.tenant_id=? AND s.id=?
                """, (rs, row) -> new DecisionContext(rs.getObject("id", UUID.class),
                rs.getObject("portfolio_id", UUID.class), rs.getString("status"), rs.getString("feasibility"),
                (Integer) rs.getObject("current_version"), rs.getObject("version_id", UUID.class),
                rs.getInt("version"), rs.getObject("load_forecast_version_id", UUID.class),
                rs.getObject("pv_forecast_version_id", UUID.class), rs.getObject("tariff_plan_id", UUID.class),
                rs.getObject("device_config_snapshot_id", UUID.class), rs.getString("algorithm_version"),
                rs.getString("content_sha256"), instant(rs, "interval_start"), instant(rs, "interval_end")),
                tenantId, scheduleId).stream().findFirst();
    }

    void insertDecision(UUID id, UUID tenantId, DecisionContext context,
            ScheduleDecisionRequest request, String actor, String key, Instant decidedAt) {
        jdbc.update("""
                INSERT INTO schedule_approval(id,tenant_id,schedule_id,schedule_version_id,schedule_version,
                    decision,reason,actor_id,idempotency_key,decided_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, tenantId, context.scheduleId(), context.versionId(), context.version(),
                request.decision(), request.reason(), actor, key, Timestamp.from(decidedAt));
    }

    void markSchedule(UUID tenantId, UUID scheduleId, String status) {
        int changed = jdbc.update("""
                UPDATE schedule SET status=?,updated_at=now()
                WHERE tenant_id=? AND id=? AND status='VALIDATED' AND feasibility='FEASIBLE'
                """, status, tenantId, scheduleId);
        if (changed != 1) throw new IllegalStateException("locked schedule was not decision eligible");
    }

    int createCommands(UUID tenantId, DecisionContext context, Instant createdAt, Duration ackTimeout) {
        List<CommandSeed> targets = jdbc.query("""
                SELECT st.device_id,st.interval_start,st.interval_end,st.setpoint_kw,d.config_version
                FROM schedule_target st
                JOIN device d ON d.tenant_id=st.tenant_id AND d.id=st.device_id
                WHERE st.tenant_id=? AND st.schedule_version_id=?
                ORDER BY st.interval_start,st.device_id
                """, (rs, row) -> new CommandSeed(rs.getObject("device_id", UUID.class),
                rs.getTimestamp("interval_start").toInstant(), rs.getTimestamp("interval_end").toInstant(),
                rs.getBigDecimal("setpoint_kw"), rs.getLong("config_version")), tenantId, context.versionId());
        Map<UUID, CommandSeed> lastByDevice = new LinkedHashMap<>();
        for (CommandSeed target : targets) {
            String key = "%s:v%d:%s:%s:set-power".formatted(context.scheduleId(), context.version(),
                    target.deviceId(), target.start());
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("active_power_kw", target.setpointKw());
            parameters.put("duration_seconds", Duration.between(target.start(), target.end()).toSeconds());
            jdbc.update("""
                    INSERT INTO command(id,tenant_id,device_id,schedule_id,schedule_version_id,idempotency_key,
                        action,parameters_json,safety_config_version,status,not_before,expires_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,'SET_POWER',?::jsonb,?,'CREATED',?,?,?,?)
                    ON CONFLICT (tenant_id,idempotency_key) DO NOTHING
                    """, UUID.randomUUID(), tenantId, target.deviceId(), context.scheduleId(), context.versionId(),
                    key, json(parameters), target.configVersion(), Timestamp.from(target.start()),
                    Timestamp.from(target.start().plus(ackTimeout)), Timestamp.from(createdAt), Timestamp.from(createdAt));
            lastByDevice.put(target.deviceId(), target);
        }
        for (CommandSeed target : lastByDevice.values()) {
            String key = "%s:v%d:%s:%s:schedule-end-stop".formatted(context.scheduleId(), context.version(),
                    target.deviceId(), target.end());
            jdbc.update("""
                    INSERT INTO command(id,tenant_id,device_id,schedule_id,schedule_version_id,idempotency_key,
                        action,parameters_json,safety_config_version,status,not_before,expires_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,'STOP',?::jsonb,?,'CREATED',?,?,?,?)
                    ON CONFLICT (tenant_id,idempotency_key) DO NOTHING
                    """, UUID.randomUUID(), tenantId, target.deviceId(), context.scheduleId(), context.versionId(),
                    key, json(Map.of("reason", "SCHEDULE_END")), target.configVersion(), Timestamp.from(target.end()),
                    Timestamp.from(target.end().plus(ackTimeout)), Timestamp.from(createdAt), Timestamp.from(createdAt));
        }
        return jdbc.queryForObject("SELECT count(*) FROM command WHERE tenant_id=? AND schedule_id=?",
                Integer.class, tenantId, context.scheduleId());
    }

    Optional<ScheduleDecisionResponse> findDecision(UUID tenantId, UUID decisionId) {
        return jdbc.query("""
                SELECT sa.*,(SELECT count(*) FROM command c WHERE c.tenant_id=sa.tenant_id
                    AND c.schedule_id=sa.schedule_id) AS command_count
                FROM schedule_approval sa WHERE sa.tenant_id=? AND sa.id=?
                """, (rs, row) -> new ScheduleDecisionResponse(rs.getObject("id", UUID.class),
                rs.getObject("schedule_id", UUID.class), rs.getObject("schedule_version_id", UUID.class),
                rs.getInt("schedule_version"), rs.getString("decision"), rs.getString("reason"),
                rs.getString("actor_id"), rs.getTimestamp("decided_at").toInstant(), rs.getInt("command_count")),
                tenantId, decisionId).stream().findFirst();
    }
    private Optional<ScheduleDecisionResponse> findDecisionForSchedule(UUID tenantId, UUID scheduleId) {
        return jdbc.query("""
                SELECT sa.*,(SELECT count(*) FROM command c WHERE c.tenant_id=sa.tenant_id
                    AND c.schedule_id=sa.schedule_id) AS command_count
                FROM schedule_approval sa WHERE sa.tenant_id=? AND sa.schedule_id=?
                """, (rs, row) -> new ScheduleDecisionResponse(rs.getObject("id", UUID.class),
                rs.getObject("schedule_id", UUID.class), rs.getObject("schedule_version_id", UUID.class),
                rs.getInt("schedule_version"), rs.getString("decision"), rs.getString("reason"),
                rs.getString("actor_id"), rs.getTimestamp("decided_at").toInstant(), rs.getInt("command_count")),
                tenantId, scheduleId).stream().findFirst();
    }

    void approvalOutbox(UUID tenantId, DecisionContext context, String actor, Instant decidedAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema", "vpp.schedule.approved"); event.put("schema_version", 2);
        event.put("event_id", UUID.randomUUID()); event.put("event_type", "ScheduleApproved");
        event.put("tenant_id", tenantId); event.put("schedule_id", context.scheduleId());
        event.put("schedule_version", context.version()); event.put("portfolio_id", context.portfolioId());
        event.put("interval_start", context.intervalStart()); event.put("interval_end", context.intervalEnd());
        event.put("load_forecast_version_id", context.loadForecastVersionId());
        event.put("pv_forecast_version_id", context.pvForecastVersionId());
        event.put("tariff_plan_id", context.tariffPlanId());
        event.put("device_config_snapshot_id", context.deviceConfigSnapshotId());
        event.put("algorithm_version", context.algorithmVersion()); event.put("approved_by", actor);
        event.put("approved_at", decidedAt); event.put("content_sha256", context.contentSha256());
        scheduleDecisionOutbox(tenantId, context.scheduleId(), event);
    }

    void rejectionOutbox(UUID tenantId, DecisionContext context, String actor, String reason, Instant decidedAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema", "vpp.schedule.decision"); event.put("schema_version", 1);
        event.put("event_id", UUID.randomUUID()); event.put("event_type", "ScheduleRejected");
        event.put("tenant_id", tenantId); event.put("schedule_id", context.scheduleId());
        event.put("schedule_version", context.version()); event.put("rejected_by", actor);
        event.put("rejected_at", decidedAt); event.put("reason", reason);
        scheduleDecisionOutbox(tenantId, context.scheduleId(), event);
    }

    private void scheduleDecisionOutbox(UUID tenantId, UUID scheduleId, Map<String, Object> event) {
        jdbc.update("""
                INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,topic,message_key,payload_json)
                VALUES (?,?,'SCHEDULE',?,'vpp.schedule.events.v1',?,?::jsonb)
                """, UUID.randomUUID(), tenantId, scheduleId.toString(), tenantId + ":" + scheduleId, json(event));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Optional<ScheduleVersionResponse> version(UUID tenantId, UUID scheduleId, int version) {
        return jdbc.query("""
                SELECT * FROM schedule_version WHERE tenant_id=? AND schedule_id=? AND version=?
                """, (rs, row) -> new ScheduleVersionResponse(rs.getObject("id", UUID.class), rs.getInt("version"),
                rs.getObject("load_forecast_version_id", UUID.class), rs.getObject("pv_forecast_version_id", UUID.class),
                rs.getObject("tariff_plan_id", UUID.class), rs.getObject("device_config_snapshot_id", UUID.class),
                rs.getString("algorithm_name"), rs.getString("algorithm_version"), rs.getBigDecimal("objective_value"),
                jsonNode(rs.getString("summary_json")), rs.getString("content_sha256"),
                rs.getTimestamp("created_at").toInstant(), intervals(tenantId, rs.getObject("id", UUID.class)),
                targets(tenantId, rs.getObject("id", UUID.class))), tenantId, scheduleId, version).stream().findFirst();
    }

    private List<ScheduleIntervalResponse> intervals(UUID tenantId, UUID versionId) {
        return jdbc.query("SELECT * FROM schedule_interval WHERE tenant_id=? AND schedule_version_id=? ORDER BY interval_start",
                (rs, row) -> new ScheduleIntervalResponse(rs.getTimestamp("interval_start").toInstant(),
                rs.getTimestamp("interval_end").toInstant(), rs.getBigDecimal("load_kw"), rs.getBigDecimal("pv_kw"),
                rs.getBigDecimal("price_per_kwh"), rs.getBigDecimal("baseline_grid_kw"),
                rs.getBigDecimal("planned_grid_kw"), rs.getBigDecimal("baseline_cost"),
                rs.getBigDecimal("planned_cost")), tenantId, versionId);
    }

    private List<ScheduleTargetResponse> targets(UUID tenantId, UUID versionId) {
        return jdbc.query("SELECT * FROM schedule_target WHERE tenant_id=? AND schedule_version_id=? ORDER BY interval_start,device_id",
                (rs, row) -> new ScheduleTargetResponse(rs.getObject("device_id", UUID.class),
                rs.getTimestamp("interval_start").toInstant(), rs.getTimestamp("interval_end").toInstant(),
                rs.getBigDecimal("charge_kw"), rs.getBigDecimal("discharge_kw"), rs.getBigDecimal("setpoint_kw"),
                rs.getBigDecimal("soc_start_pct"), rs.getBigDecimal("soc_end_pct")), tenantId, versionId);
    }

    private ScheduleResponse schedule(ResultSet rs, int row) throws SQLException {
        return new ScheduleResponse(rs.getObject("id", UUID.class), rs.getObject("portfolio_id", UUID.class),
                rs.getObject("schedule_date", LocalDate.class), rs.getString("timezone"), rs.getString("status"),
                rs.getString("feasibility"), (Integer) rs.getObject("current_version"), rs.getString("failure_code"),
                mapper.convertValue(jsonNode(rs.getString("reasons_json")),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                jsonNode(rs.getString("input_json")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private JsonNode jsonNode(String value) {
        try { return mapper.readTree(value); } catch (JacksonException exception) { throw new IllegalStateException(exception); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (JacksonException exception) { throw new IllegalStateException(exception); }
    }

    record SiteConfig(UUID id, String timezone, BigDecimal gridLimitKw, String status) {}
    record Capability(String name, String unit, BigDecimal min, BigDecimal max, BigDecimal fallback,
            long configVersion) {}
    record BatteryConfig(UUID id, UUID siteId, long configVersion, Map<String, Capability> capabilities) {}
    record TariffPrice(Instant start, Instant end, BigDecimal price) {}
    record TariffConfig(UUID id, String timezone, Instant validFrom, Instant validTo, String currency,
            int version, List<TariffPrice> intervals) {}
    record DecisionContext(UUID scheduleId, UUID portfolioId, String status, String feasibility,
            Integer currentVersion, UUID versionId, int version, UUID loadForecastVersionId,
            UUID pvForecastVersionId, UUID tariffPlanId, UUID deviceConfigSnapshotId,
            String algorithmVersion, String contentSha256, Instant intervalStart, Instant intervalEnd) {}
    private record CommandSeed(UUID deviceId, Instant start, Instant end, BigDecimal setpointKw,
            long configVersion) {}
}
