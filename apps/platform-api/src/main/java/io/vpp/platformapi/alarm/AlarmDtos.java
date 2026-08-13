package io.vpp.platformapi.alarm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public final class AlarmDtos {
    private AlarmDtos() {}

    public record CreateRuleRequest(
            @NotBlank @Size(max = 128) @Pattern(regexp = "^[a-z][a-z0-9-]*$") String code,
            @NotBlank @Size(max = 160) String name,
            @JsonProperty("rule_type") @NotBlank @Pattern(regexp = "OFFLINE|STALE|SOC_LOW|SOC_HIGH|POWER_LOW|POWER_HIGH|SUDDEN_CHANGE|COMMAND_TIMEOUT") String ruleType,
            @JsonProperty("device_type") @Pattern(regexp = "METER|PV_INVERTER|BATTERY|EV_CHARGER") String deviceType,
            @Size(max = 64) String metric,
            BigDecimal threshold,
            @JsonProperty("clear_threshold") BigDecimal clearThreshold,
            @JsonProperty("duration_seconds") @NotNull Integer durationSeconds,
            @NotBlank @Pattern(regexp = "INFO|WARNING|MAJOR|CRITICAL") String severity,
            @NotNull Boolean enabled) {}

    public record RuleResponse(UUID id, String code, String name,
            @JsonProperty("rule_type") String ruleType,
            @JsonProperty("device_type") String deviceType, String metric,
            BigDecimal threshold, @JsonProperty("clear_threshold") BigDecimal clearThreshold,
            @JsonProperty("duration_seconds") int durationSeconds, String severity,
            int version, boolean enabled, @JsonProperty("coverage_status") String coverageStatus,
            @JsonProperty("created_at") Instant createdAt) {}

    public record AlarmResponse(UUID id, @JsonProperty("rule_id") UUID ruleId,
            @JsonProperty("rule_code") String ruleCode, @JsonProperty("rule_name") String ruleName,
            @JsonProperty("object_type") String objectType, @JsonProperty("object_id") UUID objectId,
            @JsonProperty("object_name") String objectName, @JsonProperty("external_code") String externalCode,
            @JsonProperty("portfolio_id") UUID portfolioId, @JsonProperty("site_id") UUID siteId,
            String state, String severity,
            @JsonProperty("first_occurred_at") Instant firstOccurredAt,
            @JsonProperty("last_occurred_at") Instant lastOccurredAt,
            @JsonProperty("occurrence_count") long occurrenceCount,
            @JsonProperty("acknowledged_at") Instant acknowledgedAt,
            @JsonProperty("acknowledged_by") String acknowledgedBy,
            @JsonProperty("recovered_at") Instant recoveredAt,
            @JsonProperty("closed_at") Instant closedAt,
            JsonNode evidence) {}

    public record TransitionResponse(UUID id, @JsonProperty("event_type") String eventType,
            @JsonProperty("from_state") String fromState, @JsonProperty("to_state") String toState,
            @JsonProperty("actor_id") String actorId, String reason, JsonNode evidence,
            @JsonProperty("occurred_at") Instant occurredAt) {}

    public record AlarmDetailResponse(AlarmResponse alarm, List<TransitionResponse> timeline) {}

    public record ActionRequest(@NotBlank @Size(max = 500) String reason) {}

    public record CoverageResponse(@JsonProperty("enabled_rules") int enabledRules,
            @JsonProperty("evaluated_rules") int evaluatedRules,
            @JsonProperty("unsupported_rules") List<String> unsupportedRules,
            String status) {}
}
