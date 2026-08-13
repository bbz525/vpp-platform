package io.vpp.platformapi.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import io.vpp.platformapi.audit.AuditDtos.AuditEventResponse;
import io.vpp.platformapi.schedule.ScheduleDtos.ScheduleDecisionResponse;

public final class CommandDtos {
    private CommandDtos() {}

    public record StopCommandRequest(@NotBlank @Size(max = 500) String reason) {}
    public record CommandResponse(UUID id, @JsonProperty("device_id") UUID deviceId,
            @JsonProperty("schedule_id") UUID scheduleId,
            @JsonProperty("schedule_version_id") UUID scheduleVersionId,
            @JsonProperty("parent_command_id") UUID parentCommandId,
            @JsonProperty("idempotency_key") String idempotencyKey, String action, JsonNode parameters,
            String status, @JsonProperty("not_before") Instant notBefore,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("terminal_at") Instant terminalAt,
            @JsonProperty("last_reason_code") String lastReasonCode,
            @JsonProperty("last_message") String lastMessage, JsonNode actual,
            @JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt) {}
    public record CommandAttemptResponse(@JsonProperty("attempt_no") int attemptNo,
            @JsonProperty("dispatched_at") Instant dispatchedAt,
            @JsonProperty("latest_ack_at") Instant latestAckAt,
            @JsonProperty("latest_ack_status") String latestAckStatus,
            @JsonProperty("error_code") String errorCode) {}
    public record CommandEventResponse(UUID id, @JsonProperty("source_event_id") UUID sourceEventId,
            @JsonProperty("event_type") String eventType, @JsonProperty("from_status") String fromStatus,
            @JsonProperty("reported_status") String reportedStatus, boolean applied,
            @JsonProperty("reason_code") String reasonCode, String message, JsonNode actual,
            @JsonProperty("occurred_at") Instant occurredAt, @JsonProperty("received_at") Instant receivedAt) {}
    public record CommandDetailResponse(CommandResponse command, List<CommandAttemptResponse> attempts,
            List<CommandEventResponse> events) {}
    public record ScheduleExecutionResponse(@JsonProperty("schedule_id") UUID scheduleId,
            @JsonProperty("command_total") int commandTotal,
            @JsonProperty("command_offset") int commandOffset,
            @JsonProperty("command_limit") int commandLimit,
            ScheduleDecisionResponse decision, List<CommandDetailResponse> commands,
            List<AuditEventResponse> audit) {}
}
