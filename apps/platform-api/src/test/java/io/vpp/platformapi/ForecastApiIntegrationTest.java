package io.vpp.platformapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.forecast.ForecastHistoryClient;
import io.vpp.platformapi.forecast.ForecastServiceClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.6-alpine:///vpp",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "platform.forecast.enabled=true"
})
@Import(ForecastApiIntegrationTest.Fakes.class)
class ForecastApiIntegrationTest {
    private static final String TENANT = "7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941";
    private static final String SUBJECT = "local-admin";

    @Autowired
    private ServletWebServerApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void forecastRunIsIdempotentVersionedTenantScopedAndAppendOnly() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode portfolio = send("POST", "/api/v1/portfolios", "portfolio-" + suffix,
                "{\"name\":\"Forecast Portfolio " + suffix + "\"}", TENANT, SUBJECT, 201);
        send("POST", "/api/v1/sites", "site-key-" + suffix, """
                {"portfolio_id":"%s","name":"Forecast Site","timezone":"Asia/Shanghai",
                 "grid_connection_limit_kw":500}
                """.formatted(portfolio.path("id").asText()), TENANT, SUBJECT, 201);
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
        String loadRequest = request(portfolio, day, "LOAD_KW");
        JsonNode accepted = send("POST", "/api/v1/forecast-runs", "forecast-" + suffix,
                loadRequest, TENANT, SUBJECT, 202);
        JsonNode replay = send("POST", "/api/v1/forecast-runs", "forecast-" + suffix,
                loadRequest, TENANT, SUBJECT, 202);
        assertThat(replay.path("id").asText()).isEqualTo(accepted.path("id").asText());

        JsonNode detail = awaitTerminal(accepted.path("id").asText());
        assertThat(detail.path("run").path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode base = detail.path("forecast");
        assertThat(base.path("points").size()).isEqualTo(96);
        assertThat(base.path("version").asInt()).isEqualTo(1);
        assertThat(base.path("source").asText()).isEqualTo("MODEL");

        JsonNode firstPoint = base.path("points").get(0);
        String overrideBody = """
                {"reason":"operator correction","points":[{"interval_start":"%s","value":123.5}]}
                """.formatted(firstPoint.path("interval_start").asText());
        JsonNode override = send("POST", "/api/v1/forecasts/" + base.path("id").asText()
                + "/overrides", "override-" + suffix, overrideBody, TENANT, SUBJECT, 201);
        JsonNode overrideReplay = send("POST", "/api/v1/forecasts/" + base.path("id").asText()
                + "/overrides", "override-" + suffix, overrideBody, TENANT, SUBJECT, 201);
        assertThat(overrideReplay.path("id").asText()).isEqualTo(override.path("id").asText());
        assertThat(override.path("version").asInt()).isEqualTo(2);
        assertThat(override.path("source").asText()).isEqualTo("MANUAL_OVERRIDE");
        assertThat(override.path("points").get(0).path("value").decimalValue())
                .isEqualByComparingTo("123.5");
        JsonNode unchanged = send("GET", "/api/v1/forecasts/" + base.path("id").asText(),
                null, null, TENANT, SUBJECT, 200);
        assertThat(unchanged.path("points").get(0).path("value").asDouble()).isEqualTo(50.0);

        assertThatThrownBy(() -> jdbc.update("UPDATE forecast_point SET value=0 WHERE forecast_version_id=?",
                UUID.fromString(base.path("id").asText()))).hasMessageContaining("append-only");
        UUID otherTenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant(id,name,status) VALUES (?,?,'ACTIVE')", otherTenant, "Other");
        jdbc.update("INSERT INTO app_user(id,tenant_id,external_subject,status) VALUES (?,?,?,'ACTIVE')",
                otherUser, otherTenant, "other-admin");
        jdbc.update("INSERT INTO app_user_role(tenant_id,user_id,role) VALUES (?,?,'TENANT_ADMIN')",
                otherTenant, otherUser);
        send("GET", "/api/v1/forecast-runs/" + accepted.path("id").asText(), null, null,
                otherTenant.toString(), "other-admin", 404);

        JsonNode pv = send("POST", "/api/v1/forecast-runs", "forecast-pv-" + suffix,
                request(portfolio, day, "PV_POWER_KW"), TENANT, SUBJECT, 202);
        JsonNode pvDetail = awaitTerminal(pv.path("id").asText());
        assertThat(pvDetail.path("run").path("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(pvDetail.path("run").path("reasons").get(0).asText())
                .isEqualTo("MINIMUM_HISTORY_DAYS_NOT_MET");
        assertThat(pvDetail.path("forecast").isNull()).isTrue();
    }

    private static JsonNode successful(ObjectMapper mapper, LocalDate day, String timezone, Instant cutoff) {
        var response = mapper.createObjectNode();
        response.put("status", "SUCCEEDED");
        response.put("model_name", "SIMILAR_DAY_AVERAGE");
        response.put("model_version", "similar-day-average-v1");
        response.put("feature_version", "quarter-hour-local-calendar-v1");
        response.put("data_cutoff", cutoff.toString());
        response.putObject("dataset").put("weather_source", "NONE").put("valid_history_days", 28);
        response.putObject("validation").put("method", "ROLLING_ORIGIN")
                .put("evaluated_points", 672).put("mae_kw", 2.1).put("wape", 0.03);
        var points = response.putArray("points");
        Instant start = day.atStartOfDay(ZoneId.of(timezone)).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneId.of(timezone)).toInstant();
        for (Instant cursor = start; cursor.isBefore(end); cursor = cursor.plus(Duration.ofMinutes(15))) {
            var point = points.addObject();
            point.put("interval_start", cursor.toString());
            point.put("interval_end", cursor.plus(Duration.ofMinutes(15)).toString());
            point.put("value", 50.0);
            point.put("unit", "kW");
            point.put("quality", "ESTIMATED");
        }
        return response;
    }

    private static JsonNode insufficient(ObjectMapper mapper, Instant cutoff) {
        var response = mapper.createObjectNode();
        response.put("status", "INSUFFICIENT_DATA");
        response.put("failure_code", "INSUFFICIENT_DATA");
        response.put("data_cutoff", cutoff.toString());
        response.putArray("reasons").add("MINIMUM_HISTORY_DAYS_NOT_MET");
        response.putObject("dataset").put("weather_source", "NONE").put("valid_history_days", 0);
        response.putObject("validation").put("evaluated_points", 0);
        response.putArray("points");
        return response;
    }

    private String request(JsonNode portfolio, LocalDate day, String metric) {
        return """
                {"target_type":"PORTFOLIO","target_id":"%s","forecast_date":"%s","metric":"%s"}
                """.formatted(portfolio.path("id").asText(), day, metric);
    }

    private JsonNode awaitTerminal(String id) throws Exception {
        JsonNode response = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            response = send("GET", "/api/v1/forecast-runs/" + id, null, null,
                    TENANT, SUBJECT, 200);
            String status = response.path("run").path("status").asText();
            if (List.of("SUCCEEDED", "INSUFFICIENT_DATA", "FAILED").contains(status)) return response;
            Thread.sleep(25);
        }
        throw new AssertionError("forecast run did not complete: " + response);
    }

    private JsonNode send(String method, String path, String key, String body, String tenant,
            String subject, int expectedStatus) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("X-VPP-Tenant-Id", tenant).header("X-VPP-Subject", subject);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return mapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        @Primary
        ForecastHistoryClient testHistory() {
            return (tenantId, targetType, targetId, metric, cutoff) -> List.of();
        }

        @Bean
        @Primary
        ForecastServiceClient testForecastClient(ObjectMapper mapper) {
            return (date, timezone, metric, cutoff, observations) -> "LOAD_KW".equals(metric)
                    ? successful(mapper, date, timezone, cutoff) : insufficient(mapper, cutoff);
        }
    }
}
