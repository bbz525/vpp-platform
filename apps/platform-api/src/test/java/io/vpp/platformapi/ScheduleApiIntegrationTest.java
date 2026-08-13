package io.vpp.platformapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.6-alpine:///vpp",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
class ScheduleApiIntegrationTest {
    private static final UUID TENANT = UUID.fromString("7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941");
    @Autowired ServletWebServerApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void createsImmutableValidatedPlanAndPersistsExplicitInfeasibility() throws Exception {
        Fixture fixture = fixture();
        String body = request(fixture, "50");
        JsonNode created = send("POST", "/api/v1/schedules", "schedule-" + fixture.portfolio(), body, 202);
        JsonNode replay = send("POST", "/api/v1/schedules", "schedule-" + fixture.portfolio(), body, 202);

        assertThat(replay.path("schedule").path("id").asText())
                .isEqualTo(created.path("schedule").path("id").asText());
        assertThat(created.path("schedule").path("status").asText()).isEqualTo("VALIDATED");
        assertThat(created.path("schedule").path("feasibility").asText()).isEqualTo("FEASIBLE");
        assertThat(created.path("version").path("algorithm_name").asText()).isEqualTo("RULE_BASELINE");
        assertThat(created.path("version").path("intervals").size()).isEqualTo(96);
        assertThat(created.path("version").path("targets").size()).isEqualTo(96);
        assertThat(created.path("version").path("summary").path("savings").decimalValue()).isPositive();
        assertThat(created.path("version").path("content_sha256").asText()).hasSize(64);
        UUID versionId = UUID.fromString(created.path("version").path("id").asText());
        assertThatThrownBy(() -> jdbc.update("UPDATE schedule_version SET objective_value=0 WHERE id=?", versionId))
                .hasMessageContaining("append-only");

        JsonNode failed = send("POST", "/api/v1/schedules", "infeasible-" + fixture.portfolio(),
                request(fixture, "10"), 202);
        assertThat(failed.path("schedule").path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("schedule").path("feasibility").asText()).isEqualTo("INFEASIBLE");
        assertThat(failed.path("schedule").path("reasons").get(0).asText())
                .startsWith("INITIAL_SOC_OUTSIDE_SAFE_RANGE");
        assertThat(failed.path("version").isNull()).isTrue();

        JsonNode list = send("GET", "/api/v1/schedules?portfolioId=" + fixture.portfolio(), null, null, 200);
        assertThat(list.size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_version WHERE tenant_id=?", Integer.class,
                TENANT)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE tenant_id=? AND object_type='SCHEDULE'",
                Integer.class, TENANT)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id=? AND aggregate_type='SCHEDULE'",
                Integer.class, TENANT)).isEqualTo(2);
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID portfolio = UUID.randomUUID(); UUID site = UUID.randomUUID(); UUID model = UUID.randomUUID();
        UUID battery = UUID.randomUUID(); UUID loadRun = UUID.randomUUID(); UUID pvRun = UUID.randomUUID();
        UUID loadVersion = UUID.randomUUID(); UUID pvVersion = UUID.randomUUID(); UUID tariff = UUID.randomUUID();
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
        Instant start = day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        jdbc.update("INSERT INTO portfolio(id,tenant_id,name,status) VALUES (?,?,?,'ACTIVE')", portfolio, TENANT,
                "Schedule " + suffix);
        jdbc.update("INSERT INTO site(id,tenant_id,portfolio_id,name,timezone,grid_connection_limit_kw,status) VALUES (?,?,?,?,?,100,'ACTIVE')",
                site, TENANT, portfolio, "Site " + suffix, "Asia/Shanghai");
        jdbc.update("INSERT INTO device_model(id,tenant_id,name,type,schema_version,point_schema_json,status) VALUES (?,?,?,'BATTERY',1,'{}','ACTIVE')",
                model, TENANT, "Battery " + suffix);
        jdbc.update("INSERT INTO device(id,tenant_id,site_id,model_id,external_code,name,status,config_version) VALUES (?,?,?,?,?,?,'ACTIVE',1)",
                battery, TENANT, site, model, "battery-" + suffix, "Battery");
        capability(battery, "SET_POWER", "kW", "-40", "40", "0");
        capability(battery, "ENERGY_CAPACITY_KWH", "kWh", "0", "100", "100");
        capability(battery, "SOC_RANGE_PCT", "%", "10", "90", "50");
        capability(battery, "CHARGE_EFFICIENCY", "ratio", "0.80", "1", "0.90");
        capability(battery, "DISCHARGE_EFFICIENCY", "ratio", "0.80", "1", "0.90");
        forecast(loadRun, loadVersion, portfolio, day, "LOAD_KW", start, "50");
        forecast(pvRun, pvVersion, portfolio, day, "PV_POWER_KW", start, "0");
        jdbc.update("INSERT INTO tariff_plan(id,tenant_id,name,currency,timezone,version,valid_from,valid_to) VALUES (?,?,?,'CNY','Asia/Shanghai',1,?,?)",
                tariff, TENANT, "Tariff " + suffix, Timestamp.from(start), Timestamp.from(end));
        Instant noon = start.plus(Duration.ofHours(12));
        jdbc.update("INSERT INTO tariff_interval(tenant_id,tariff_plan_id,interval_start,interval_end,price_per_kwh) VALUES (?,?,?,?,0.20)",
                TENANT, tariff, Timestamp.from(start), Timestamp.from(noon));
        jdbc.update("INSERT INTO tariff_interval(tenant_id,tariff_plan_id,interval_start,interval_end,price_per_kwh) VALUES (?,?,?,?,1.00)",
                TENANT, tariff, Timestamp.from(noon), Timestamp.from(end));
        return new Fixture(portfolio, battery, loadVersion, pvVersion, tariff, day);
    }

    private void capability(UUID battery, String name, String unit, String min, String max, String fallback) {
        jdbc.update("INSERT INTO device_capability(tenant_id,device_id,capability,unit,min_value,max_value,fallback_value,config_version) VALUES (?,?,?,?,?,?,?,1)",
                TENANT, battery, name, unit, new java.math.BigDecimal(min), new java.math.BigDecimal(max),
                new java.math.BigDecimal(fallback));
    }

    private void forecast(UUID run, UUID version, UUID portfolio, LocalDate day, String metric,
            Instant start, String value) {
        jdbc.update("INSERT INTO forecast_run(id,tenant_id,target_type,target_id,forecast_date,timezone,metric,status,data_cutoff,created_by,completed_at) VALUES (?,?,'PORTFOLIO',?,?,'Asia/Shanghai',?,'SUCCEEDED',?,'test',now())",
                run, TENANT, portfolio, day, metric, Timestamp.from(start.minus(Duration.ofHours(1))));
        jdbc.update("INSERT INTO forecast_version(id,tenant_id,forecast_run_id,version,source,model_name,model_version,feature_version,weather_source,data_cutoff,created_by) VALUES (?,?,?,1,'MODEL','TEST','1','1','NONE',?,'test')",
                version, TENANT, run, Timestamp.from(start.minus(Duration.ofHours(1))));
        for (int index = 0; index < 96; index++) {
            Instant pointStart = start.plus(Duration.ofMinutes(index * 15L));
            jdbc.update("INSERT INTO forecast_point(id,tenant_id,forecast_version_id,interval_start,interval_end,value,unit,quality) VALUES (?,?,?,?,?,?,'kW','ESTIMATED')",
                    UUID.randomUUID(), TENANT, version, Timestamp.from(pointStart),
                    Timestamp.from(pointStart.plus(Duration.ofMinutes(15))), new java.math.BigDecimal(value));
        }
    }

    private String request(Fixture fixture, String soc) {
        return """
                {"portfolio_id":"%s","schedule_date":"%s","load_forecast_version_id":"%s",
                 "pv_forecast_version_id":"%s","tariff_plan_id":"%s",
                 "battery_states":[{"device_id":"%s","initial_soc_pct":%s}]}
                """.formatted(fixture.portfolio(), fixture.day(), fixture.loadVersion(), fixture.pvVersion(),
                fixture.tariff(), fixture.battery(), soc);
    }

    private JsonNode send(String method, String path, String key, String body, int expected) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + context.getWebServer().getPort() + path)).timeout(Duration.ofSeconds(10))
                .header("X-VPP-Tenant-Id", TENANT.toString()).header("X-VPP-Subject", "local-admin");
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return mapper.readTree(response.body());
    }

    private record Fixture(UUID portfolio, UUID battery, UUID loadVersion, UUID pvVersion,
            UUID tariff, LocalDate day) {}
}
