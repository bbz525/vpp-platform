package io.vpp.platformapi.alarm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vpp.platformapi.alarm.AlarmDtos.ActionRequest;
import io.vpp.platformapi.alarm.AlarmDtos.AlarmDetailResponse;
import io.vpp.platformapi.alarm.AlarmDtos.AlarmResponse;
import io.vpp.platformapi.alarm.AlarmDtos.CoverageResponse;
import io.vpp.platformapi.alarm.AlarmDtos.CreateRuleRequest;
import io.vpp.platformapi.alarm.AlarmDtos.RuleResponse;
import io.vpp.platformapi.audit.AuditOutboxWriter;
import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.security.ActorPrincipal;

@Service
public class AlarmService {
    private final AlarmRepository repository;
    private final AlarmRealtimePublisher realtime;
    private final AuditOutboxWriter audit;

    public AlarmService(AlarmRepository repository, AlarmRealtimePublisher realtime,
            AuditOutboxWriter audit) {
        this.repository = repository; this.realtime = realtime; this.audit = audit;
    }

    @Transactional
    public RuleResponse createRule(ActorPrincipal actor, CreateRuleRequest request) {
        validateRule(request);
        RuleResponse created = repository.insertRule(actor.tenantId(), UUID.randomUUID(), request);
        audit.succeeded(actor, "ALARM_RULE_CREATED", "ALARM_RULE", created.id(), null, null, created);
        return created;
    }

    public List<RuleResponse> rules(UUID tenantId) { return repository.listRules(tenantId); }

    public CoverageResponse coverage(UUID tenantId) {
        List<RuleResponse> enabled = repository.listRules(tenantId).stream().filter(RuleResponse::enabled).toList();
        List<String> unsupported = enabled.stream().filter(rule -> "UNSUPPORTED".equals(rule.coverageStatus()))
                .map(RuleResponse::code).toList();
        return new CoverageResponse(enabled.size(), enabled.size() - unsupported.size(), unsupported,
                unsupported.isEmpty() ? "FULL" : "PARTIAL");
    }

    public List<AlarmResponse> alarms(UUID tenantId, String state, String severity,
            UUID portfolioId, int limit) {
        validateFilter(state, severity, limit);
        return repository.listAlarms(tenantId, state, severity, portfolioId, limit);
    }

    public AlarmDetailResponse detail(UUID tenantId, UUID alarmId) {
        AlarmResponse alarm = get(tenantId, alarmId);
        return new AlarmDetailResponse(alarm, repository.timeline(tenantId, alarmId));
    }

    @Transactional
    public AlarmResponse acknowledge(ActorPrincipal actor, UUID alarmId, ActionRequest request) {
        AlarmResponse before = get(actor.tenantId(), alarmId);
        if ("CLOSED".equals(before.state())) throw ApiException.conflict("closed alarm cannot be acknowledged");
        if (before.acknowledgedAt() != null) throw ApiException.conflict("alarm is already acknowledged");
        Instant now = Instant.now();
        AlarmResponse after = repository.acknowledge(actor.tenantId(), before, actor.subject(), request.reason(), now);
        emit(actor.tenantId(), after, "AlarmAcknowledged", before.state(), actor.subject(), request.reason(), now);
        audit.succeeded(actor, "ALARM_ACKNOWLEDGED", "ALARM", alarmId, request.reason(), before, after);
        return after;
    }

    @Transactional
    public AlarmDetailResponse note(ActorPrincipal actor, UUID alarmId, ActionRequest request) {
        AlarmResponse alarm = get(actor.tenantId(), alarmId);
        if ("CLOSED".equals(alarm.state())) throw ApiException.conflict("closed alarm cannot be annotated");
        repository.note(actor.tenantId(), alarm, actor.subject(), request.reason(), Instant.now());
        audit.succeeded(actor, "ALARM_NOTED", "ALARM", alarmId, request.reason(), alarm, alarm);
        return detail(actor.tenantId(), alarmId);
    }

    @Transactional
    public AlarmResponse close(ActorPrincipal actor, UUID alarmId, ActionRequest request) {
        AlarmResponse before = get(actor.tenantId(), alarmId);
        if (!List.of("ACKNOWLEDGED", "RECOVERED").contains(before.state())) {
            throw ApiException.conflict("only an acknowledged or recovered alarm can be closed");
        }
        Instant now = Instant.now();
        AlarmResponse after = repository.close(actor.tenantId(), before, actor.subject(), request.reason(), now);
        emit(actor.tenantId(), after, "AlarmClosed", before.state(), actor.subject(), request.reason(), now);
        audit.succeeded(actor, "ALARM_CLOSED", "ALARM", alarmId, request.reason(), before, after);
        return after;
    }

    @Transactional
    public AlarmResponse condition(UUID tenantId, RuleResponse rule, AlarmRepository.AlarmTarget target,
            boolean triggered, tools.jackson.databind.JsonNode evidence, Instant at) {
        AlarmResponse current = repository.findActiveForUpdate(tenantId, rule.id(), target.id()).orElse(null);
        if (triggered && current == null) {
            AlarmResponse opened = repository.open(tenantId, rule, target, at, evidence);
            emit(tenantId, opened, "AlarmOpened", null, null, null, at);
            return opened;
        }
        if (triggered && current != null && List.of("OPEN", "ACKNOWLEDGED").contains(current.state())) {
            String previousEvent = current.evidence().path("event_id").asString();
            String nextEvent = evidence.path("event_id").asString();
            if (!nextEvent.isBlank() && !nextEvent.equals(previousEvent)) {
                AlarmResponse repeated = repository.repeat(tenantId, current, at, evidence);
                emit(tenantId, repeated, "AlarmRepeated", current.state(), null, null, at);
                return repeated;
            }
            return current;
        }
        if (triggered && "RECOVERED".equals(current == null ? null : current.state())) {
            AlarmResponse reopened = repository.repeat(tenantId, current, at, evidence);
            emit(tenantId, reopened, "AlarmOpened", "RECOVERED", null, null, at);
            return reopened;
        }
        if (!triggered && current != null && List.of("OPEN", "ACKNOWLEDGED").contains(current.state())) {
            AlarmResponse recovered = repository.recover(tenantId, current, at, evidence);
            emit(tenantId, recovered, "AlarmRecovered", current.state(), null, null, at);
            return recovered;
        }
        return current;
    }

    private void emit(UUID tenantId, AlarmResponse alarm, String eventType, String fromState,
            String actor, String reason, Instant at) {
        repository.outbox(tenantId, alarm, eventType, fromState, actor, reason, at);
        realtime.publishAfterCommit(tenantId, alarm, eventType, at);
    }

    private AlarmResponse get(UUID tenantId, UUID id) {
        return repository.findAlarm(tenantId, id).orElseThrow(() -> ApiException.notFound("alarm"));
    }

    private static void validateRule(CreateRuleRequest request) {
        boolean thresholdRule = List.of("SOC_LOW", "SOC_HIGH", "POWER_LOW", "POWER_HIGH", "SUDDEN_CHANGE").contains(request.ruleType());
        if (thresholdRule && request.threshold() == null) throw ApiException.validation("threshold is required for metric rules");
        if (request.clearThreshold() != null && request.threshold() != null) {
            boolean high = List.of("SOC_HIGH", "POWER_HIGH", "SUDDEN_CHANGE").contains(request.ruleType());
            boolean low = List.of("SOC_LOW", "POWER_LOW").contains(request.ruleType());
            if (high && request.clearThreshold().compareTo(request.threshold()) >= 0) {
                throw ApiException.validation("clear_threshold must be below threshold for HIGH rules");
            }
            if (low && request.clearThreshold().compareTo(request.threshold()) <= 0) {
                throw ApiException.validation("clear_threshold must be above threshold for LOW rules");
            }
        }
        if (List.of("SOC_LOW", "SOC_HIGH").contains(request.ruleType()) && request.deviceType() != null
                && !"BATTERY".equals(request.deviceType())) throw ApiException.validation("SOC rules only apply to BATTERY devices");
        if (request.durationSeconds() < 0 || request.durationSeconds() > 86400) throw ApiException.validation("duration_seconds must be between 0 and 86400");
    }

    private static void validateFilter(String state, String severity, int limit) {
        if (state != null && !List.of("OPEN", "ACKNOWLEDGED", "RECOVERED", "CLOSED").contains(state)) throw ApiException.validation("invalid alarm state");
        if (severity != null && !List.of("INFO", "WARNING", "MAJOR", "CRITICAL").contains(severity)) throw ApiException.validation("invalid severity");
        if (limit < 1 || limit > 500) throw ApiException.validation("limit must be between 1 and 500");
    }
}
