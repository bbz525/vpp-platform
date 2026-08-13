package io.vpp.platformapi.schedule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public final class ScheduleDtos {
    private ScheduleDtos() {}

    public record BatteryStateInput(@JsonProperty("device_id") @NotNull UUID deviceId,
            @JsonProperty("initial_soc_pct") @NotNull @DecimalMin("0") @DecimalMax("100")
            BigDecimal initialSocPct) {}

    public record CreateScheduleRequest(@JsonProperty("portfolio_id") @NotNull UUID portfolioId,
            @JsonProperty("schedule_date") @NotNull LocalDate scheduleDate,
            @JsonProperty("load_forecast_version_id") @NotNull UUID loadForecastVersionId,
            @JsonProperty("pv_forecast_version_id") @NotNull UUID pvForecastVersionId,
            @JsonProperty("tariff_plan_id") @NotNull UUID tariffPlanId,
            @JsonProperty("battery_states") @NotNull @Size(max = 256)
            List<@Valid BatteryStateInput> batteryStates) {}

    public record ScheduleResponse(UUID id, @JsonProperty("portfolio_id") UUID portfolioId,
            @JsonProperty("schedule_date") LocalDate scheduleDate, String timezone, String status,
            String feasibility, @JsonProperty("current_version") Integer currentVersion,
            @JsonProperty("failure_code") String failureCode, List<String> reasons,
            JsonNode input, @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {}

    public record ScheduleIntervalResponse(@JsonProperty("interval_start") Instant intervalStart,
            @JsonProperty("interval_end") Instant intervalEnd,
            @JsonProperty("load_kw") BigDecimal loadKw, @JsonProperty("pv_kw") BigDecimal pvKw,
            @JsonProperty("price_per_kwh") BigDecimal pricePerKwh,
            @JsonProperty("baseline_grid_kw") BigDecimal baselineGridKw,
            @JsonProperty("planned_grid_kw") BigDecimal plannedGridKw,
            @JsonProperty("baseline_cost") BigDecimal baselineCost,
            @JsonProperty("planned_cost") BigDecimal plannedCost) {}

    public record ScheduleTargetResponse(@JsonProperty("device_id") UUID deviceId,
            @JsonProperty("interval_start") Instant intervalStart,
            @JsonProperty("interval_end") Instant intervalEnd,
            @JsonProperty("charge_kw") BigDecimal chargeKw,
            @JsonProperty("discharge_kw") BigDecimal dischargeKw,
            @JsonProperty("setpoint_kw") BigDecimal setpointKw,
            @JsonProperty("soc_start_pct") BigDecimal socStartPct,
            @JsonProperty("soc_end_pct") BigDecimal socEndPct) {}

    public record ScheduleVersionResponse(UUID id, int version,
            @JsonProperty("load_forecast_version_id") UUID loadForecastVersionId,
            @JsonProperty("pv_forecast_version_id") UUID pvForecastVersionId,
            @JsonProperty("tariff_plan_id") UUID tariffPlanId,
            @JsonProperty("device_config_snapshot_id") UUID deviceConfigSnapshotId,
            @JsonProperty("algorithm_name") String algorithmName,
            @JsonProperty("algorithm_version") String algorithmVersion,
            @JsonProperty("objective_value") BigDecimal objectiveValue, JsonNode summary,
            @JsonProperty("content_sha256") String contentSha256,
            @JsonProperty("created_at") Instant createdAt, List<ScheduleIntervalResponse> intervals,
            List<ScheduleTargetResponse> targets) {}

    public record ScheduleDetailResponse(ScheduleResponse schedule, ScheduleVersionResponse version) {}
}
