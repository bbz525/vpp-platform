package io.vpp.platformapi.schedule;

import static io.vpp.platformapi.schedule.ScheduleDtos.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
        return Optional.of(new ScheduleDetailResponse(schedule.get(), version));
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
}
