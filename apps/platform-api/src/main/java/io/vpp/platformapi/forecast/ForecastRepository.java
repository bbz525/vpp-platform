package io.vpp.platformapi.forecast;

import static io.vpp.platformapi.forecast.ForecastDtos.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ForecastRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ForecastRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public RunResponse insertRun(UUID tenantId, UUID id, CreateRunRequest request, String timezone,
            Instant cutoff, String actor) {
        jdbc.update("""
                INSERT INTO forecast_run
                  (id,tenant_id,target_type,target_id,forecast_date,timezone,metric,status,
                   data_cutoff,created_by)
                VALUES (?,?,?,?,?,?,?,'PENDING',?,?)
                """, id, tenantId, request.targetType(), request.targetId(), request.forecastDate(),
                timezone, request.metric(), Timestamp.from(cutoff), actor);
        return findRun(tenantId, id).orElseThrow();
    }

    public Optional<RunResponse> findRun(UUID tenantId, UUID id) {
        List<RunResponse> rows = jdbc.query("""
                SELECT r.*,v.id forecast_version_id
                FROM forecast_run r LEFT JOIN forecast_version v
                  ON v.forecast_run_id=r.id AND v.version=(SELECT max(v2.version) FROM forecast_version v2 WHERE v2.forecast_run_id=r.id)
                WHERE r.tenant_id=? AND r.id=?
                """, (result, row) -> new RunResponse(result.getObject("id", UUID.class),
                result.getString("status"), result.getString("target_type"),
                result.getObject("target_id", UUID.class), result.getObject("forecast_date", LocalDate.class),
                result.getString("timezone"), result.getString("metric"),
                result.getTimestamp("data_cutoff").toInstant(), result.getString("failure_code"),
                stringList(result.getString("reasons_json")), json(result.getString("dataset_report_json")),
                json(result.getString("validation_metrics_json")),
                result.getObject("forecast_version_id", UUID.class),
                result.getTimestamp("created_at").toInstant(), instant(result, "completed_at")), tenantId, id);
        return rows.stream().findFirst();
    }

    public List<RunResponse> listRuns(UUID tenantId, UUID targetId, int limit) {
        return jdbc.query("""
                SELECT r.*,v.id forecast_version_id
                FROM forecast_run r LEFT JOIN forecast_version v
                  ON v.forecast_run_id=r.id AND v.version=(SELECT max(v2.version) FROM forecast_version v2 WHERE v2.forecast_run_id=r.id)
                WHERE r.tenant_id=? AND (?::uuid IS NULL OR r.target_id=?)
                ORDER BY r.created_at DESC LIMIT ?
                """, (result, row) -> new RunResponse(result.getObject("id", UUID.class),
                result.getString("status"), result.getString("target_type"),
                result.getObject("target_id", UUID.class), result.getObject("forecast_date", LocalDate.class),
                result.getString("timezone"), result.getString("metric"),
                result.getTimestamp("data_cutoff").toInstant(), result.getString("failure_code"),
                stringList(result.getString("reasons_json")), json(result.getString("dataset_report_json")),
                json(result.getString("validation_metrics_json")),
                result.getObject("forecast_version_id", UUID.class),
                result.getTimestamp("created_at").toInstant(), instant(result, "completed_at")),
                tenantId, targetId, targetId, limit);
    }

    public Optional<String> siteTimezone(UUID tenantId, UUID siteId) {
        return jdbc.query("SELECT timezone FROM site WHERE tenant_id=? AND id=?",
                (result, row) -> result.getString("timezone"), tenantId, siteId)
                .stream().findFirst();
    }

    public boolean portfolioExists(UUID tenantId, UUID portfolioId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM portfolio WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, portfolioId);
        return count != null && count == 1;
    }

    public List<String> portfolioTimezones(UUID tenantId, UUID portfolioId) {
        return jdbc.query("SELECT DISTINCT timezone FROM site WHERE tenant_id=? AND portfolio_id=?",
                (result, row) -> result.getString("timezone"), tenantId, portfolioId);
    }

    public void lockRun(UUID tenantId, UUID runId) {
        jdbc.queryForObject("SELECT id FROM forecast_run WHERE tenant_id=? AND id=? FOR UPDATE",
                UUID.class, tenantId, runId);
    }

    public void running(UUID tenantId, UUID id) {
        jdbc.update("UPDATE forecast_run SET status='RUNNING',started_at=now(),updated_at=now() WHERE tenant_id=? AND id=? AND status='PENDING'",
                tenantId, id);
    }

    public void insufficient(UUID tenantId, UUID id, JsonNode response) {
        jdbc.update("""
                UPDATE forecast_run SET status='INSUFFICIENT_DATA',failure_code=?,reasons_json=?::jsonb,
                  dataset_report_json=?::jsonb,validation_metrics_json=?::jsonb,
                  completed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?
                """, response.path("failure_code").asText("INSUFFICIENT_DATA"),
                jsonText(response.path("reasons")), jsonText(response.path("dataset")),
                jsonText(response.path("validation")), tenantId, id);
    }

    public void failed(UUID tenantId, UUID id, String code) {
        jdbc.update("""
                UPDATE forecast_run SET status='FAILED',failure_code=?,completed_at=now(),updated_at=now()
                WHERE tenant_id=? AND id=? AND status IN ('PENDING','RUNNING')
                """, code, tenantId, id);
    }

    public VersionResponse succeeded(UUID tenantId, UUID runId, JsonNode response, String actor) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO forecast_version
                  (id,tenant_id,forecast_run_id,version,source,model_name,model_version,feature_version,
                   weather_source,data_cutoff,created_by)
                VALUES (?,?,?,1,'MODEL',?,?,?,?,?,?)
                """, versionId, tenantId, runId, response.path("model_name").asText(),
                response.path("model_version").asText(), response.path("feature_version").asText(),
                response.path("dataset").path("weather_source").asText("NONE"),
                Timestamp.from(Instant.parse(response.path("data_cutoff").asText())), actor);
        for (JsonNode point : response.path("points")) {
            jdbc.update("""
                    INSERT INTO forecast_point
                      (id,tenant_id,forecast_version_id,interval_start,interval_end,value,unit,quality)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), tenantId, versionId,
                    Timestamp.from(Instant.parse(point.path("interval_start").asText())),
                    Timestamp.from(Instant.parse(point.path("interval_end").asText())),
                    point.path("value").decimalValue(), point.path("unit").asText("kW"),
                    point.path("quality").asText("ESTIMATED"));
        }
        jdbc.update("""
                UPDATE forecast_run SET status='SUCCEEDED',dataset_report_json=?::jsonb,
                  validation_metrics_json=?::jsonb,completed_at=now(),updated_at=now()
                WHERE tenant_id=? AND id=?
                """, jsonText(response.path("dataset")), jsonText(response.path("validation")), tenantId, runId);
        return findVersion(tenantId, versionId).orElseThrow();
    }

    public Optional<VersionResponse> latestVersion(UUID tenantId, UUID runId) {
        List<UUID> ids = jdbc.query("SELECT id FROM forecast_version WHERE tenant_id=? AND forecast_run_id=? ORDER BY version DESC LIMIT 1",
                (rs, row) -> rs.getObject("id", UUID.class), tenantId, runId);
        return ids.isEmpty() ? Optional.empty() : findVersion(tenantId, ids.getFirst());
    }

    public Optional<VersionResponse> findVersion(UUID tenantId, UUID id) {
        List<VersionResponse> versions = jdbc.query("""
                SELECT * FROM forecast_version WHERE tenant_id=? AND id=?
                """, (rs, row) -> new VersionResponse(rs.getObject("id", UUID.class),
                rs.getObject("forecast_run_id", UUID.class), rs.getInt("version"), rs.getString("source"),
                rs.getString("model_name"), rs.getString("model_version"), rs.getString("feature_version"),
                rs.getString("weather_source"), rs.getTimestamp("data_cutoff").toInstant(),
                rs.getObject("overridden_from_id", UUID.class), rs.getString("override_reason"),
                rs.getTimestamp("created_at").toInstant(), points(tenantId, id)), tenantId, id);
        return versions.stream().findFirst();
    }

    public VersionResponse override(UUID tenantId, VersionResponse base, OverrideRequest request, String actor) {
        UUID id = UUID.randomUUID(); int version = base.version() + 1;
        jdbc.update("""
                INSERT INTO forecast_version
                  (id,tenant_id,forecast_run_id,version,source,model_name,model_version,feature_version,
                   weather_source,data_cutoff,overridden_from_id,override_reason,created_by)
                VALUES (?,?,?,?,'MANUAL_OVERRIDE',?,?,?,?,?,?,?,?)
                """, id, tenantId, base.forecastRunId(), version, base.modelName(), base.modelVersion(),
                base.featureVersion(), base.weatherSource(), Timestamp.from(base.dataCutoff()), base.id(),
                request.reason(), actor);
        java.util.Map<Instant, java.math.BigDecimal> changes = request.points().stream().collect(
                java.util.stream.Collectors.toMap(OverridePoint::intervalStart, OverridePoint::value));
        for (PointResponse point : base.points()) {
            java.math.BigDecimal value = changes.getOrDefault(point.intervalStart(), point.value());
            String quality = changes.containsKey(point.intervalStart()) ? "OVERRIDDEN" : point.quality();
            jdbc.update("INSERT INTO forecast_point(id,tenant_id,forecast_version_id,interval_start,interval_end,value,unit,quality) VALUES (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), tenantId, id, Timestamp.from(point.intervalStart()),
                    Timestamp.from(point.intervalEnd()), value, point.unit(), quality);
        }
        return findVersion(tenantId, id).orElseThrow();
    }

    public void outbox(UUID tenantId, RunResponse run, String eventType, Instant at) {
        var payload = mapper.createObjectNode();
        payload.put("schema_version", 1); payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", eventType); payload.put("tenant_id", tenantId.toString());
        payload.put("forecast_run_id", run.id().toString()); payload.put("target_type", run.targetType());
        payload.put("target_id", run.targetId().toString()); payload.put("forecast_date", run.forecastDate().toString());
        payload.put("metric", run.metric()); payload.put("status", run.status());
        payload.put("data_cutoff", run.dataCutoff().toString()); payload.put("occurred_at", at.toString());
        if (run.forecastVersionId() != null) payload.put("forecast_version_id", run.forecastVersionId().toString());
        if (run.failureCode() != null) payload.put("failure_code", run.failureCode());
        jdbc.update("INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,topic,message_key,payload_json) VALUES (?,?,'FORECAST',?,'vpp.forecast.events.v1',?,?::jsonb)",
                UUID.randomUUID(), tenantId, run.id().toString(), tenantId + ":" + run.id(), jsonText(payload));
    }

    private List<PointResponse> points(UUID tenantId, UUID versionId) {
        return jdbc.query("SELECT interval_start,interval_end,value,unit,quality FROM forecast_point WHERE tenant_id=? AND forecast_version_id=? ORDER BY interval_start",
                (rs, row) -> new PointResponse(rs.getTimestamp("interval_start").toInstant(),
                rs.getTimestamp("interval_end").toInstant(), rs.getBigDecimal("value"),
                rs.getString("unit"), rs.getString("quality")), tenantId, versionId);
    }
    private Instant instant(java.sql.ResultSet rs,String name)throws java.sql.SQLException{Timestamp v=rs.getTimestamp(name);return v==null?null:v.toInstant();}
    private JsonNode json(String value){if(value==null)return null;try{return mapper.readTree(value);}catch(JacksonException e){throw new IllegalStateException(e);}}
    private List<String> stringList(String value){JsonNode node=json(value);if(node==null)return List.of();return java.util.stream.StreamSupport.stream(node.spliterator(),false).map(JsonNode::asText).toList();}
    private String jsonText(JsonNode value){try{return mapper.writeValueAsString(value);}catch(JacksonException e){throw new IllegalStateException(e);}}
}
