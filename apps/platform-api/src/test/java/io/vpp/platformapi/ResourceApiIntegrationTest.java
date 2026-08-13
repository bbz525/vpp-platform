package io.vpp.platformapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
class ResourceApiIntegrationTest {
    private static final String TENANT = "7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941";
    private static final String SUBJECT = "local-admin";
    private static final String INTERNAL_TOKEN = "local-dev-platform-internal-token-only";

    @Autowired
    private ServletWebServerApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void resourceCredentialDisableAuditAndTenantIsolationFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode portfolio = send("POST", "/api/v1/portfolios", "portfolio-" + suffix, """
                {"name":"East China Portfolio %s"}
                """.formatted(suffix), TENANT, SUBJECT, 201);
        JsonNode site = send("POST", "/api/v1/sites", "site-key-" + suffix, """
                {"portfolio_id":"%s","name":"Shanghai Site %s","timezone":"Asia/Shanghai",
                 "grid_connection_limit_kw":500.0}
                """.formatted(portfolio.get("id").asString(), suffix), TENANT, SUBJECT, 201);
        JsonNode model = send("POST", "/api/v1/device-models", "model-key-" + suffix, """
                {"name":"BESS Model %s","type":"BATTERY","schema_version":1,
                 "point_schema":{"active_power_kw":{"unit":"kW"},"soc_pct":{"unit":"%%"}}}
                """.formatted(suffix), TENANT, SUBJECT, 201);
        JsonNode device = send("POST", "/api/v1/devices", "device-key-" + suffix, """
                {"site_id":"%s","model_id":"%s","external_code":"bess-%s","name":"BESS %s",
                 "capabilities":[{"capability":"SET_POWER","unit":"kW","min_value":-100,
                 "max_value":100,"fallback_value":0}]}
                """.formatted(site.get("id").asString(), model.get("id").asString(), suffix, suffix),
                TENANT, SUBJECT, 201);
        String deviceId = device.get("id").asString();

        String credential = "test-device-secret-" + suffix;
        JsonNode credentialRef = send("POST", "/api/v1/devices/" + deviceId + "/credentials",
                "cred-key-" + suffix, """
                        {"credential":"%s","reason":"initial provisioning"}
                        """.formatted(credential), TENANT, SUBJECT, 201);
        assertThat(credentialRef.toString()).doesNotContain(credential).doesNotContain("verifier");

        send("PUT", "/api/v1/devices/" + deviceId + "/status", "enable-key-" + suffix,
                "{\"status\":\"ACTIVE\",\"reason\":\"commissioned\"}", TENANT, SUBJECT, 200);
        JsonNode identities = sendInternal(200);
        assertThat(identities.toString()).contains("bess-" + suffix).doesNotContain(credential);
        JsonNode catalog = sendInternalCatalog(200);
        assertThat(catalog.toString()).contains("bess-" + suffix)
                .contains(site.get("id").asString())
                .contains(portfolio.get("id").asString())
                .contains("BATTERY");

        UUID otherTenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id,name,status) VALUES (?,?,'ACTIVE')", otherTenant,
                "Other tenant");
        jdbc.update("INSERT INTO app_user (id,tenant_id,external_subject,status) VALUES (?,?,?,'ACTIVE')",
                otherUser, otherTenant, "other-admin");
        jdbc.update("INSERT INTO app_user_role (tenant_id,user_id,role) VALUES (?,?, 'TENANT_ADMIN')",
                otherTenant, otherUser);
        send("GET", "/api/v1/devices/" + deviceId, null, null,
                otherTenant.toString(), "other-admin", 404);

        send("PUT", "/api/v1/devices/" + deviceId + "/status", "disable-key-" + suffix,
                "{\"status\":\"DISABLED\",\"reason\":\"security review\"}", TENANT, SUBJECT, 200);
        assertThat(sendInternal(200).toString()).doesNotContain("bess-" + suffix);
        assertThat(sendInternalCatalog(200).toString()).contains("bess-" + suffix)
                .contains("DISABLED");

        JsonNode audits = send("GET", "/api/v1/audit-events?objectType=DEVICE&objectId=" + deviceId,
                null, null, TENANT, SUBJECT, 200);
        assertThat(audits.size()).isGreaterThanOrEqualTo(4);
        Integer outboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND aggregate_type='AUDIT_EVENT'",
                Integer.class, UUID.fromString(TENANT));
        assertThat(outboxCount).isGreaterThanOrEqualTo(7);

        UUID auditId = UUID.fromString(audits.get(0).get("id").asString());
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET reason='tampered' WHERE id=?", auditId))
                .hasMessageContaining("append-only");
    }

    @Test
    void rejectsOverlappingTariffAndReplaysIdempotentCreate() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String valid = """
                {"name":"TOU %s","currency":"CNY","timezone":"Asia/Shanghai","version":1,
                 "valid_from":"2026-08-12T00:00:00Z","valid_to":"2026-08-13T00:00:00Z",
                 "intervals":[
                   {"start":"2026-08-12T00:00:00Z","end":"2026-08-12T08:00:00Z","price_per_kwh":0.35},
                   {"start":"2026-08-12T08:00:00Z","end":"2026-08-13T00:00:00Z","price_per_kwh":0.85}]}
                """.formatted(suffix);
        JsonNode first = send("POST", "/api/v1/tariff-plans", "tariff-key-" + suffix,
                valid, TENANT, SUBJECT, 201);
        JsonNode replay = send("POST", "/api/v1/tariff-plans", "tariff-key-" + suffix,
                valid, TENANT, SUBJECT, 201);
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());

        String overlap = """
                {"name":"Bad TOU %s","currency":"CNY","timezone":"Asia/Shanghai","version":1,
                 "valid_from":"2026-08-12T00:00:00Z","valid_to":"2026-08-13T00:00:00Z",
                 "intervals":[
                   {"start":"2026-08-12T00:00:00Z","end":"2026-08-12T10:00:00Z","price_per_kwh":0.35},
                   {"start":"2026-08-12T08:00:00Z","end":"2026-08-13T00:00:00Z","price_per_kwh":0.85}]}
                """.formatted(suffix);
        JsonNode error = send("POST", "/api/v1/tariff-plans", "bad-tariff-" + suffix,
                overlap, TENANT, SUBJECT, 422);
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void returnsContractErrorForInvalidIdempotencyKey() throws Exception {
        JsonNode error = send("POST", "/api/v1/portfolios", "short", "{\"name\":\"Invalid\"}",
                TENANT, SUBJECT, 422);
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("trace_id").asString()).hasSize(32);
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

    private JsonNode sendInternal(int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/internal/device-identities"))
                .header("X-VPP-Internal-Token", INTERNAL_TOKEN).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return mapper.readTree(response.body());
    }

    private JsonNode sendInternalCatalog(int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/internal/device-catalog"))
                .header("X-VPP-Internal-Token", INTERNAL_TOKEN).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return mapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path);
    }
}
