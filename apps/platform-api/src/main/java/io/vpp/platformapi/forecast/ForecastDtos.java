package io.vpp.platformapi.forecast;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public final class ForecastDtos {
    private ForecastDtos() {}

    public record CreateRunRequest(
            @JsonProperty("target_type") @NotBlank @Pattern(regexp = "SITE|PORTFOLIO") String targetType,
            @JsonProperty("target_id") @NotNull UUID targetId,
            @JsonProperty("forecast_date") @NotNull LocalDate forecastDate,
            @NotBlank @Pattern(regexp = "LOAD_KW|PV_POWER_KW") String metric) {}

    public record RunResponse(UUID id, String status,
            @JsonProperty("target_type") String targetType, @JsonProperty("target_id") UUID targetId,
            @JsonProperty("forecast_date") LocalDate forecastDate, String timezone, String metric,
            @JsonProperty("data_cutoff") Instant dataCutoff,
            @JsonProperty("failure_code") String failureCode, List<String> reasons,
            @JsonProperty("dataset_report") JsonNode datasetReport,
            @JsonProperty("validation_metrics") JsonNode validationMetrics,
            @JsonProperty("forecast_version_id") UUID forecastVersionId,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("completed_at") Instant completedAt) {}

    public record PointResponse(@JsonProperty("interval_start") Instant intervalStart,
            @JsonProperty("interval_end") Instant intervalEnd, BigDecimal value,
            String unit, String quality) {}

    public record VersionResponse(UUID id, @JsonProperty("forecast_run_id") UUID forecastRunId,
            int version, String source, @JsonProperty("model_name") String modelName,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("feature_version") String featureVersion,
            @JsonProperty("weather_source") String weatherSource,
            @JsonProperty("data_cutoff") Instant dataCutoff,
            @JsonProperty("overridden_from_id") UUID overriddenFromId,
            @JsonProperty("override_reason") String overrideReason,
            @JsonProperty("created_at") Instant createdAt, List<PointResponse> points) {}

    public record RunDetailResponse(RunResponse run, VersionResponse forecast) {}

    public record OverridePoint(@JsonProperty("interval_start") @NotNull Instant intervalStart,
            @NotNull BigDecimal value) {}

    public record OverrideRequest(@NotBlank @Size(max = 500) String reason,
            @NotEmpty List<@Valid OverridePoint> points) {}
}
