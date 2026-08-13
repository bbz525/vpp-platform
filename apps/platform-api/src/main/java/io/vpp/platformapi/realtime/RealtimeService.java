package io.vpp.platformapi.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.config.RealtimeProperties;
import io.vpp.platformapi.realtime.RealtimeDtos.Coverage;
import io.vpp.platformapi.realtime.RealtimeDtos.DeviceSnapshot;
import io.vpp.platformapi.realtime.RealtimeDtos.MetricValue;
import io.vpp.platformapi.realtime.RealtimeDtos.PortfolioSnapshot;
import io.vpp.platformapi.realtime.RealtimeDtos.PowerSummary;
import io.vpp.platformapi.realtime.RealtimeDtos.SiteSnapshot;
import io.vpp.platformapi.realtime.RealtimeDtos.TelemetryQueryResponse;
import io.vpp.platformapi.realtime.RealtimeDtos.TelemetrySeries;
import io.vpp.platformapi.resource.ResourceRepository;
import io.vpp.platformapi.resource.ResourceRepository.RealtimeDeviceMeta;

@Service
public class RealtimeService {
    private static final Pattern METRIC = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Map<String, String> DEFAULT_UNITS = Map.of(
            "active_power_kw", "kW", "energy_import_kwh", "kWh",
            "energy_generated_kwh", "kWh", "irradiance_w_m2", "W/m²",
            "soc_pct", "%", "available_capacity_kwh", "kWh",
            "session_energy_kwh", "kWh");

    private final RealtimeProperties properties;
    private final ResourceRepository resources;
    private final StringRedisTemplate redis;
    private final ClickHouseQueryClient clickHouse;
    private final ObjectMapper mapper;

    public RealtimeService(RealtimeProperties properties, ResourceRepository resources,
            StringRedisTemplate redis, ClickHouseQueryClient clickHouse,
            ObjectMapper mapper) {
        this.properties = properties;
        this.resources = resources;
        this.redis = redis;
        this.clickHouse = clickHouse;
        this.mapper = mapper;
    }

    public PortfolioSnapshot portfolioSnapshot(UUID tenantId, UUID portfolioId) {
        requireEnabled();
        var portfolio = resources.findPortfolio(tenantId, portfolioId)
                .orElseThrow(() -> ApiException.notFound("portfolio"));
        List<RealtimeDeviceMeta> rows = resources.realtimeDevices(tenantId, portfolioId);
        Map<UUID, List<DeviceWithState>> sites = new LinkedHashMap<>();
        Map<UUID, RealtimeDeviceMeta> siteMeta = new LinkedHashMap<>();
        for (RealtimeDeviceMeta row : rows) {
            siteMeta.putIfAbsent(row.siteId(), row);
            if (row.deviceId() == null) continue;
            sites.computeIfAbsent(row.siteId(), ignored -> new ArrayList<>())
                    .add(new DeviceWithState(row, readState(tenantId, row)));
        }
        List<SiteSnapshot> siteSnapshots = new ArrayList<>();
        List<DeviceWithState> allDevices = new ArrayList<>();
        for (Map.Entry<UUID, RealtimeDeviceMeta> entry : siteMeta.entrySet()) {
            List<DeviceWithState> devices = sites.getOrDefault(entry.getKey(), List.of());
            List<DeviceWithState> operational = operational(devices);
            allDevices.addAll(operational);
            List<DeviceSnapshot> snapshots = devices.stream().map(this::deviceSnapshot).toList();
            siteSnapshots.add(new SiteSnapshot(entry.getKey(), entry.getValue().siteName(),
                    entry.getValue().timezone(), freshness(operational), coverage(operational), snapshots));
        }
        siteSnapshots.sort(Comparator.comparing(SiteSnapshot::siteName));
        Instant observedAt = allDevices.stream().map(DeviceWithState::state)
                .flatMap(Optional::stream).map(DeviceState::producedAt).max(Comparator.naturalOrder())
                .orElse(null);
        Double age = observedAt == null ? null : ageSeconds(observedAt);
        return new PortfolioSnapshot(portfolioId, portfolio.name(), observedAt, age,
                freshness(allDevices), quality(allDevices), coverage(allDevices),
                power(allDevices), List.copyOf(siteSnapshots));
    }

    public TelemetryQueryResponse query(UUID tenantId, String targetType, UUID targetId,
            String metric, Instant from, Instant to) {
        requireEnabled();
        String type = targetType.toUpperCase(Locale.ROOT);
        if (!List.of("PORTFOLIO", "SITE", "DEVICE").contains(type)) {
            throw ApiException.validation("targetType must be PORTFOLIO, SITE or DEVICE");
        }
        if (!METRIC.matcher(metric).matches()) {
            throw ApiException.validation("metric has an invalid format");
        }
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(7)) > 0) {
            throw ApiException.validation("telemetry query range must be positive and at most 7 days");
        }
        authorizeTarget(tenantId, type, targetId);
        int bucket = bucketSeconds(Duration.between(from, to));
        String targetColumn = switch (type) {
            case "PORTFOLIO" -> "portfolio_id";
            case "SITE" -> "site_id";
            default -> "device_id";
        };
        List<TelemetrySeries> result = clickHouse.query(tenantId, targetColumn, targetId,
                metric, from, to, bucket);
        return new TelemetryQueryResponse(type, targetId, metric, from, to, bucket, result);
    }

    private void authorizeTarget(UUID tenantId, String type, UUID id) {
        switch (type) {
            case "PORTFOLIO" -> resources.findPortfolio(tenantId, id)
                    .orElseThrow(() -> ApiException.notFound("portfolio"));
            case "SITE" -> resources.findSite(tenantId, id)
                    .orElseThrow(() -> ApiException.notFound("site"));
            default -> resources.findDevice(tenantId, id)
                    .orElseThrow(() -> ApiException.notFound("device"));
        }
    }

    private DeviceSnapshot deviceSnapshot(DeviceWithState value) {
        RealtimeDeviceMeta meta = value.meta();
        DeviceState state = value.state().orElse(null);
        if (state == null) {
            return new DeviceSnapshot(meta.deviceId(), meta.externalCode(), meta.deviceName(),
                    meta.deviceType(), meta.deviceStatus(), "EMPTY", "UNKNOWN", null, null, List.of());
        }
        double age = ageSeconds(state.producedAt());
        List<MetricValue> metrics = state.metrics().properties().stream()
                .filter(entry -> entry.getValue().isNumber())
                .map(entry -> new MetricValue(entry.getKey(), entry.getValue().asDouble(),
                        unit(meta.pointSchema(), entry.getKey())))
                .sorted(Comparator.comparing(MetricValue::name)).toList();
        String status = age > properties.staleAfter().toSeconds() ? "STALE" : "LIVE";
        return new DeviceSnapshot(meta.deviceId(), meta.externalCode(), meta.deviceName(),
                meta.deviceType(), meta.deviceStatus(), status, state.quality(), state.producedAt(),
                age, metrics);
    }

    private Optional<DeviceState> readState(UUID tenantId, RealtimeDeviceMeta meta) {
        String key = "vpp:{" + tenantId + "}:device:" + meta.deviceId() + ":state";
        try {
            Map<Object, Object> values = redis.opsForHash().entries(key);
            if (values.isEmpty() || values.get("produced_at") == null) return Optional.empty();
            JsonNode metrics = mapper.readTree(String.valueOf(values.getOrDefault("metrics_json", "{}")));
            return Optional.of(new DeviceState(Instant.parse(values.get("produced_at").toString()),
                    String.valueOf(values.getOrDefault("quality_status", "UNKNOWN")), metrics));
        } catch (DataAccessException exception) {
            throw ApiException.unavailable("realtime state is temporarily unavailable");
        } catch (RuntimeException exception) {
            throw ApiException.unavailable("realtime state contains unreadable data");
        }
    }

    private Coverage coverage(List<DeviceWithState> devices) {
        int reporting = 0;
        int live = 0;
        for (DeviceWithState device : devices) {
            if (device.state().isPresent()) {
                reporting++;
                if (ageSeconds(device.state().orElseThrow().producedAt())
                        <= properties.staleAfter().toSeconds()) live++;
            }
        }
        return new Coverage(devices.size(), reporting, live);
    }

    private String freshness(List<DeviceWithState> devices) {
        Coverage value = coverage(devices);
        if (value.reportingDevices() == 0) return "EMPTY";
        if (value.liveDevices() == 0) return "STALE";
        if (value.reportingDevices() < value.totalDevices()
                || value.liveDevices() < value.reportingDevices()
                || devices.stream().flatMap(device -> device.state().stream())
                        .anyMatch(state -> !"VALID".equals(state.quality()))) return "PARTIAL";
        return "LIVE";
    }

    private static String quality(List<DeviceWithState> devices) {
        List<DeviceState> states = devices.stream().flatMap(device -> device.state().stream()).toList();
        if (states.isEmpty()) return "UNKNOWN";
        return states.stream().allMatch(state -> "VALID".equals(state.quality())) ? "VALID" : "SUSPECT";
    }

    private static List<DeviceWithState> operational(List<DeviceWithState> devices) {
        return devices.stream().filter(device -> "ACTIVE".equals(device.meta().deviceStatus())).toList();
    }

    private static PowerSummary power(List<DeviceWithState> devices) {
        return new PowerSummary(sumPower(devices, "METER"), sumPower(devices, "PV_INVERTER"),
                sumPower(devices, "BATTERY"));
    }

    private static Double sumPower(List<DeviceWithState> devices, String type) {
        double sum = 0;
        boolean found = false;
        for (DeviceWithState device : devices) {
            if (!type.equals(device.meta().deviceType()) || device.state().isEmpty()) continue;
            JsonNode value = device.state().orElseThrow().metrics().get("active_power_kw");
            if (value != null && value.isNumber()) {
                sum += value.asDouble();
                found = true;
            }
        }
        return found ? sum : null;
    }

    private static String unit(JsonNode pointSchema, String metric) {
        JsonNode definition = pointSchema == null ? null : pointSchema.get(metric);
        if (definition != null && definition.isObject() && definition.get("unit") != null) {
            return definition.get("unit").asString();
        }
        return DEFAULT_UNITS.getOrDefault(metric, "");
    }

    private static int bucketSeconds(Duration range) {
        if (range.compareTo(Duration.ofHours(2)) <= 0) return 5;
        if (range.compareTo(Duration.ofDays(1)) <= 0) return 60;
        return 900;
    }

    private static double ageSeconds(Instant observedAt) {
        return Math.max(0, Duration.between(observedAt, Instant.now()).toMillis() / 1000.0);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw ApiException.unavailable("realtime services are disabled");
        }
    }

    private record DeviceState(Instant producedAt, String quality, JsonNode metrics) {
    }

    private record DeviceWithState(RealtimeDeviceMeta meta, Optional<DeviceState> state) {
    }

}
