package io.vpp.platformapi.resource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.resource.ResourceDtos.CredentialReferenceResponse;
import io.vpp.platformapi.resource.ResourceDtos.DeviceCapabilityInput;
import io.vpp.platformapi.resource.ResourceDtos.DeviceCapabilityResponse;
import io.vpp.platformapi.resource.ResourceDtos.DeviceModelResponse;
import io.vpp.platformapi.resource.ResourceDtos.DeviceResponse;
import io.vpp.platformapi.resource.ResourceDtos.PortfolioResponse;
import io.vpp.platformapi.resource.ResourceDtos.SiteResponse;
import io.vpp.platformapi.resource.ResourceDtos.TariffIntervalInput;
import io.vpp.platformapi.resource.ResourceDtos.TariffPlanResponse;

@Repository
public class ResourceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ResourceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PortfolioResponse insertPortfolio(UUID tenantId, UUID id, String name) {
        jdbc.update("""
                INSERT INTO portfolio (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')
                """, id, tenantId, name);
        return findPortfolio(tenantId, id).orElseThrow();
    }

    public Optional<PortfolioResponse> findPortfolio(UUID tenantId, UUID id) {
        return first(jdbc.query("""
                SELECT id, name, status, created_at FROM portfolio WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new PortfolioResponse(result.getObject("id", UUID.class),
                        result.getString("name"), result.getString("status"),
                        result.getTimestamp("created_at").toInstant()), tenantId, id));
    }

    public List<PortfolioResponse> listPortfolios(UUID tenantId) {
        return jdbc.query("""
                SELECT id, name, status, created_at FROM portfolio
                WHERE tenant_id = ? ORDER BY name, id
                """, (result, row) -> new PortfolioResponse(result.getObject("id", UUID.class),
                        result.getString("name"), result.getString("status"),
                        result.getTimestamp("created_at").toInstant()), tenantId);
    }

    public SiteResponse insertSite(UUID tenantId, UUID id, UUID portfolioId, String name,
            String timezone, java.math.BigDecimal gridLimit) {
        jdbc.update("""
                INSERT INTO site
                    (id, tenant_id, portfolio_id, name, timezone, grid_connection_limit_kw, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, id, tenantId, portfolioId, name, timezone, gridLimit);
        return findSite(tenantId, id).orElseThrow();
    }

    public Optional<SiteResponse> findSite(UUID tenantId, UUID id) {
        return first(jdbc.query("""
                SELECT id, portfolio_id, name, timezone, grid_connection_limit_kw, status, created_at
                FROM site WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new SiteResponse(result.getObject("id", UUID.class),
                        result.getObject("portfolio_id", UUID.class), result.getString("name"),
                        result.getString("timezone"), result.getBigDecimal("grid_connection_limit_kw"),
                        result.getString("status"), result.getTimestamp("created_at").toInstant()),
                tenantId, id));
    }

    public List<SiteResponse> listSites(UUID tenantId, UUID portfolioId) {
        return jdbc.query("""
                SELECT id, portfolio_id, name, timezone, grid_connection_limit_kw, status, created_at
                FROM site WHERE tenant_id = ? AND portfolio_id = ? ORDER BY name, id
                """, (result, row) -> new SiteResponse(result.getObject("id", UUID.class),
                        result.getObject("portfolio_id", UUID.class), result.getString("name"),
                        result.getString("timezone"), result.getBigDecimal("grid_connection_limit_kw"),
                        result.getString("status"), result.getTimestamp("created_at").toInstant()),
                tenantId, portfolioId);
    }

    public DeviceModelResponse insertModel(UUID tenantId, UUID id, String name, String type,
            int schemaVersion, JsonNode pointSchema) {
        jdbc.update("""
                INSERT INTO device_model
                    (id, tenant_id, name, type, schema_version, point_schema_json, status)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'ACTIVE')
                """, id, tenantId, name, type, schemaVersion, json(pointSchema));
        return findModel(tenantId, id).orElseThrow();
    }

    public Optional<DeviceModelResponse> findModel(UUID tenantId, UUID id) {
        return first(jdbc.query("""
                SELECT id, name, type, schema_version, point_schema_json::text, status, created_at
                FROM device_model WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new DeviceModelResponse(result.getObject("id", UUID.class),
                        result.getString("name"), result.getString("type"),
                        result.getInt("schema_version"), readJson(result.getString("point_schema_json")),
                        result.getString("status"), result.getTimestamp("created_at").toInstant()),
                tenantId, id));
    }

    public DeviceResponse insertDevice(UUID tenantId, UUID id, UUID siteId, UUID modelId,
            String externalCode, String name, List<DeviceCapabilityInput> capabilities) {
        jdbc.update("""
                INSERT INTO device
                    (id, tenant_id, site_id, model_id, external_code, name, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                """, id, tenantId, siteId, modelId, externalCode, name);
        for (DeviceCapabilityInput capability : capabilities) {
            jdbc.update("""
                    INSERT INTO device_capability
                        (tenant_id, device_id, capability, unit, min_value, max_value,
                         fallback_value, config_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                    """, tenantId, id, capability.capability(), capability.unit(),
                    capability.minValue(), capability.maxValue(), capability.fallbackValue());
        }
        return findDevice(tenantId, id).orElseThrow();
    }

    public Optional<DeviceResponse> findDevice(UUID tenantId, UUID id) {
        List<DeviceResponse> devices = jdbc.query(deviceSelect() + " AND d.id = ?",
                (result, row) -> mapDevice(result, List.of()), tenantId, id);
        if (devices.isEmpty()) {
            return Optional.empty();
        }
        DeviceResponse device = devices.getFirst();
        return Optional.of(withCapabilities(device, findCapabilities(tenantId, id)));
    }

    public Optional<DeviceResponse> findDeviceByExternalCode(UUID tenantId, String externalCode) {
        List<DeviceResponse> devices = jdbc.query(deviceSelect() + " AND d.external_code = ?",
                (result, row) -> mapDevice(result, List.of()), tenantId, externalCode);
        if (devices.isEmpty()) {
            return Optional.empty();
        }
        DeviceResponse device = devices.getFirst();
        return Optional.of(withCapabilities(device, findCapabilities(tenantId, device.id())));
    }

    public List<DeviceResponse> listDevices(UUID tenantId) {
        Map<UUID, List<DeviceCapabilityResponse>> capabilities = new LinkedHashMap<>();
        jdbc.query("""
                SELECT device_id, capability, unit, min_value, max_value, fallback_value, config_version
                FROM device_capability WHERE tenant_id = ? ORDER BY device_id, capability
                """, (RowCallbackHandler) result -> capabilities.computeIfAbsent(result.getObject("device_id", UUID.class),
                        ignored -> new ArrayList<>()).add(mapCapability(result)), tenantId);
        return jdbc.query(deviceSelect() + " ORDER BY d.external_code",
                (result, row) -> {
                    DeviceResponse device = mapDevice(result, List.of());
                    return withCapabilities(device, capabilities.getOrDefault(device.id(), List.of()));
                }, tenantId);
    }

    public DeviceResponse updateDeviceStatus(UUID tenantId, UUID id, String status) {
        int changed = jdbc.update("""
                UPDATE device SET status = ?, config_version = config_version + 1,
                    lock_version = lock_version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, status, tenantId, id);
        if (changed == 0) {
            throw ApiException.notFound("device");
        }
        return findDevice(tenantId, id).orElseThrow();
    }

    public void incrementDeviceConfigVersion(UUID tenantId, UUID id) {
        jdbc.update("""
                UPDATE device SET config_version = config_version + 1,
                    lock_version = lock_version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, id);
    }

    public boolean hasActiveCredential(UUID tenantId, UUID deviceId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM credential_ref
                WHERE tenant_id = ? AND device_id = ? AND status = 'ACTIVE'
                """, Integer.class, tenantId, deviceId);
        return count != null && count > 0;
    }

    public CredentialReferenceResponse rotateCredential(UUID tenantId, UUID deviceId,
            UUID credentialId, String verifier) {
        jdbc.update("""
                UPDATE credential_ref SET status = 'REVOKED'
                WHERE tenant_id = ? AND device_id = ? AND status = 'ACTIVE'
                """, tenantId, deviceId);
        jdbc.update("""
                INSERT INTO credential_ref
                    (id, tenant_id, device_id, secret_ref, credential_verifier, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, credentialId, tenantId, deviceId,
                "db://credential-ref/" + credentialId, verifier);
        incrementDeviceConfigVersion(tenantId, deviceId);
        return findCredential(tenantId, credentialId).orElseThrow();
    }

    public Optional<CredentialReferenceResponse> findCredential(UUID tenantId, UUID id) {
        return first(jdbc.query("""
                SELECT id, device_id, status, rotated_at FROM credential_ref
                WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new CredentialReferenceResponse(
                        result.getObject("id", UUID.class),
                        result.getObject("device_id", UUID.class), result.getString("status"),
                        result.getTimestamp("rotated_at").toInstant()), tenantId, id));
    }

    public TariffPlanResponse insertTariff(UUID tenantId, UUID id, String name, String currency,
            String timezone, int version, Instant validFrom, Instant validTo,
            List<TariffIntervalInput> intervals) {
        jdbc.update("""
                INSERT INTO tariff_plan
                    (id, tenant_id, name, currency, timezone, version, valid_from, valid_to)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, name, currency, timezone, version,
                Timestamp.from(validFrom), Timestamp.from(validTo));
        for (TariffIntervalInput interval : intervals) {
            jdbc.update("""
                    INSERT INTO tariff_interval
                        (tenant_id, tariff_plan_id, interval_start, interval_end, price_per_kwh)
                    VALUES (?, ?, ?, ?, ?)
                    """, tenantId, id, Timestamp.from(interval.start()), Timestamp.from(interval.end()),
                    interval.pricePerKwh());
        }
        return findTariff(tenantId, id).orElseThrow();
    }

    public Optional<TariffPlanResponse> findTariff(UUID tenantId, UUID id) {
        return first(jdbc.query("""
                SELECT p.id, p.name, p.currency, p.timezone, p.version, p.valid_from, p.valid_to,
                       p.created_at, count(i.interval_start) AS interval_count
                FROM tariff_plan p JOIN tariff_interval i
                  ON i.tenant_id = p.tenant_id AND i.tariff_plan_id = p.id
                WHERE p.tenant_id = ? AND p.id = ?
                GROUP BY p.id
                """, (result, row) -> new TariffPlanResponse(result.getObject("id", UUID.class),
                        result.getString("name"), result.getString("currency").trim(),
                        result.getString("timezone"), result.getInt("version"),
                        result.getTimestamp("valid_from").toInstant(),
                        result.getTimestamp("valid_to").toInstant(), result.getInt("interval_count"),
                        result.getTimestamp("created_at").toInstant()), tenantId, id));
    }

    public List<TariffPlanResponse> listTariffs(UUID tenantId) {
        return jdbc.query("""
                SELECT p.id,p.name,p.currency,p.timezone,p.version,p.valid_from,p.valid_to,p.created_at,
                       count(i.interval_start) AS interval_count
                FROM tariff_plan p JOIN tariff_interval i
                  ON i.tenant_id=p.tenant_id AND i.tariff_plan_id=p.id
                WHERE p.tenant_id=? GROUP BY p.id ORDER BY p.valid_from DESC,p.version DESC
                """, (result, row) -> new TariffPlanResponse(result.getObject("id", UUID.class),
                result.getString("name"), result.getString("currency").trim(), result.getString("timezone"),
                result.getInt("version"), result.getTimestamp("valid_from").toInstant(),
                result.getTimestamp("valid_to").toInstant(), result.getInt("interval_count"),
                result.getTimestamp("created_at").toInstant()), tenantId);
    }

    public List<InternalDeviceIdentity> activeIdentities() {
        return jdbc.query("""
                SELECT d.tenant_id, d.external_code, c.credential_verifier, d.config_version
                FROM device d JOIN tenant t ON t.id = d.tenant_id
                JOIN credential_ref c ON c.tenant_id = d.tenant_id AND c.device_id = d.id
                WHERE d.status = 'ACTIVE' AND t.status = 'ACTIVE' AND c.status = 'ACTIVE'
                ORDER BY d.tenant_id, d.external_code
                """, (result, row) -> new InternalDeviceIdentity(
                        result.getObject("tenant_id", UUID.class), result.getString("external_code"),
                        result.getString("credential_verifier"), result.getLong("config_version")));
    }

    public record InternalDeviceIdentity(UUID tenantId, String deviceId,
            String credentialVerifier, long configVersion) {
    }

    public List<InternalDeviceCatalogEntry> deviceCatalog() {
        return jdbc.query("""
                SELECT d.tenant_id, d.id AS device_id, d.external_code, d.site_id,
                       s.portfolio_id, m.type AS device_type, m.schema_version,
                       m.point_schema_json::text, d.status AS device_status, d.config_version
                FROM device d
                JOIN tenant t ON t.id = d.tenant_id
                JOIN site s ON s.tenant_id = d.tenant_id AND s.id = d.site_id
                JOIN portfolio p ON p.tenant_id = d.tenant_id AND p.id = s.portfolio_id
                JOIN device_model m ON m.tenant_id = d.tenant_id AND m.id = d.model_id
                WHERE t.status = 'ACTIVE'
                ORDER BY d.tenant_id, d.external_code
                """, (result, row) -> new InternalDeviceCatalogEntry(
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("device_id", UUID.class),
                        result.getString("external_code"),
                        result.getObject("site_id", UUID.class),
                        result.getObject("portfolio_id", UUID.class),
                        result.getString("device_type"), result.getInt("schema_version"),
                        readJson(result.getString("point_schema_json")),
                        result.getString("device_status"), result.getLong("config_version")));
    }

    public List<RealtimeDeviceMeta> realtimeDevices(UUID tenantId, UUID portfolioId) {
        return jdbc.query("""
                SELECT p.id AS portfolio_id, p.name AS portfolio_name,
                       s.id AS site_id, s.name AS site_name, s.timezone,
                       d.id AS device_id, d.external_code, d.name AS device_name,
                       d.status AS device_status, m.type AS device_type,
                       m.point_schema_json::text AS point_schema
                FROM portfolio p
                JOIN site s ON s.tenant_id = p.tenant_id AND s.portfolio_id = p.id
                LEFT JOIN device d ON d.tenant_id = s.tenant_id AND d.site_id = s.id
                LEFT JOIN device_model m ON m.tenant_id = d.tenant_id AND m.id = d.model_id
                WHERE p.tenant_id = ? AND p.id = ?
                ORDER BY s.name, d.external_code
                """, (result, row) -> new RealtimeDeviceMeta(
                        result.getObject("portfolio_id", UUID.class), result.getString("portfolio_name"),
                        result.getObject("site_id", UUID.class), result.getString("site_name"),
                        result.getString("timezone"), result.getObject("device_id", UUID.class),
                        result.getString("external_code"), result.getString("device_name"),
                        result.getString("device_status"), result.getString("device_type"),
                        result.getString("point_schema") == null ? mapper.createObjectNode()
                                : readJson(result.getString("point_schema"))),
                tenantId, portfolioId);
    }

    public record RealtimeDeviceMeta(UUID portfolioId, String portfolioName, UUID siteId,
            String siteName, String timezone, UUID deviceId, String externalCode,
            String deviceName, String deviceStatus, String deviceType, JsonNode pointSchema) {
    }

    public record InternalDeviceCatalogEntry(UUID tenantId, UUID deviceId, String externalCode,
            UUID siteId, UUID portfolioId, String deviceType, int schemaVersion,
            JsonNode pointSchema, String deviceStatus, long configVersion) {
    }

    private List<DeviceCapabilityResponse> findCapabilities(UUID tenantId, UUID deviceId) {
        return jdbc.query("""
                SELECT capability, unit, min_value, max_value, fallback_value, config_version
                FROM device_capability WHERE tenant_id = ? AND device_id = ? ORDER BY capability
                """, (result, row) -> mapCapability(result), tenantId, deviceId);
    }

    private static DeviceCapabilityResponse mapCapability(ResultSet result) throws SQLException {
        return new DeviceCapabilityResponse(result.getString("capability"), result.getString("unit"),
                result.getBigDecimal("min_value"), result.getBigDecimal("max_value"),
                result.getBigDecimal("fallback_value"), result.getLong("config_version"));
    }

    private static String deviceSelect() {
        return """
                SELECT d.id, d.site_id, d.model_id, d.external_code, d.name, d.status,
                       d.config_version, d.created_at, d.updated_at,
                       EXISTS (SELECT 1 FROM credential_ref c WHERE c.tenant_id = d.tenant_id
                           AND c.device_id = d.id AND c.status = 'ACTIVE') AS credential_configured
                FROM device d WHERE d.tenant_id = ?
                """;
    }

    private static DeviceResponse mapDevice(ResultSet result,
            List<DeviceCapabilityResponse> capabilities) throws SQLException {
        return new DeviceResponse(result.getObject("id", UUID.class),
                result.getObject("site_id", UUID.class), result.getObject("model_id", UUID.class),
                result.getString("external_code"), result.getString("name"),
                result.getString("status"), result.getLong("config_version"),
                result.getBoolean("credential_configured"), capabilities,
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private static DeviceResponse withCapabilities(DeviceResponse device,
            List<DeviceCapabilityResponse> capabilities) {
        return new DeviceResponse(device.id(), device.siteId(), device.modelId(),
                device.externalCode(), device.name(), device.status(), device.configVersion(),
                device.credentialConfigured(), List.copyOf(capabilities), device.createdAt(),
                device.updatedAt());
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("database contains invalid JSON", exception);
        }
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("cannot serialize point schema", exception);
        }
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
