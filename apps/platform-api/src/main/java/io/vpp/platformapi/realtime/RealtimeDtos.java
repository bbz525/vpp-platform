package io.vpp.platformapi.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class RealtimeDtos {
    private RealtimeDtos() {
    }

    public record PortfolioSnapshot(
            @JsonProperty("portfolio_id") UUID portfolioId,
            @JsonProperty("portfolio_name") String portfolioName,
            @JsonProperty("observed_at") Instant observedAt,
            @JsonProperty("freshness_seconds") Double freshnessSeconds,
            @JsonProperty("freshness_status") String freshnessStatus,
            String quality,
            Coverage coverage,
            PowerSummary power,
            List<SiteSnapshot> sites) {
    }

    public record Coverage(
            @JsonProperty("total_devices") int totalDevices,
            @JsonProperty("reporting_devices") int reportingDevices,
            @JsonProperty("live_devices") int liveDevices) {
    }

    public record PowerSummary(
            @JsonProperty("net_grid_power_kw") Double netGridPowerKw,
            @JsonProperty("pv_power_kw") Double pvPowerKw,
            @JsonProperty("battery_power_kw") Double batteryPowerKw) {
    }

    public record SiteSnapshot(
            @JsonProperty("site_id") UUID siteId,
            @JsonProperty("site_name") String siteName,
            String timezone,
            @JsonProperty("freshness_status") String freshnessStatus,
            Coverage coverage,
            List<DeviceSnapshot> devices) {
    }

    public record DeviceSnapshot(
            @JsonProperty("device_id") UUID deviceId,
            @JsonProperty("external_code") String externalCode,
            @JsonProperty("device_name") String deviceName,
            @JsonProperty("device_type") String deviceType,
            @JsonProperty("device_status") String deviceStatus,
            @JsonProperty("freshness_status") String freshnessStatus,
            String quality,
            @JsonProperty("observed_at") Instant observedAt,
            @JsonProperty("freshness_seconds") Double freshnessSeconds,
            List<MetricValue> metrics) {
    }

    public record MetricValue(String name, Double value, String unit) {
    }

    public record TelemetryQueryResponse(
            @JsonProperty("target_type") String targetType,
            @JsonProperty("target_id") UUID targetId,
            String metric,
            @JsonProperty("from") Instant from,
            @JsonProperty("to") Instant to,
            @JsonProperty("bucket_seconds") int bucketSeconds,
            List<TelemetrySeries> series) {
    }

    public record TelemetrySeries(
            @JsonProperty("external_code") String externalCode,
            @JsonProperty("device_type") String deviceType,
            String unit,
            List<TelemetryPoint> points) {
    }

    public record TelemetryPoint(
            @JsonProperty("observed_at") Instant observedAt,
            double value,
            String quality) {
    }
}
