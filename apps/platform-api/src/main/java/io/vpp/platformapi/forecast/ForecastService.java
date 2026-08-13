package io.vpp.platformapi.forecast;

import static io.vpp.platformapi.forecast.ForecastDtos.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;

import io.vpp.platformapi.audit.AuditOutboxWriter;
import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.common.IdempotencyService;
import io.vpp.platformapi.config.ForecastProperties;
import io.vpp.platformapi.security.ActorPrincipal;

@Service
public class ForecastService {
    private final ForecastRepository repository;
    private final ForecastHistoryClient history;
    private final ForecastServiceClient client;
    private final ForecastProperties properties;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter audit;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate transactions;

    public ForecastService(ForecastRepository repository, ForecastHistoryClient history,
            ForecastServiceClient client, ForecastProperties properties,
            IdempotencyService idempotency, AuditOutboxWriter audit,
            @Qualifier("forecastExecutor") ThreadPoolTaskExecutor executor,
            TransactionTemplate transactions) {
        this.repository = repository;
        this.history = history;
        this.client = client;
        this.properties = properties;
        this.idempotency = idempotency;
        this.audit = audit;
        this.executor = executor;
        this.transactions = transactions;
    }

    @Transactional
    public RunResponse create(ActorPrincipal actor, String key, CreateRunRequest request) {
        if (!properties.enabled()) {
            throw ApiException.forecastUnavailable("forecast generation is disabled");
        }
        var replay = idempotency.replay(actor.tenantId(), "CREATE_FORECAST_RUN", key, request);
        if (replay.isPresent()) {
            return getRun(actor.tenantId(), replay.get());
        }
        String timezone = resolveTimezone(actor.tenantId(), request);
        LocalDate tomorrow = LocalDate.now(ZoneId.of(timezone)).plusDays(1);
        if (request.forecastDate().isBefore(tomorrow)
                || request.forecastDate().isAfter(tomorrow.plusDays(6))) {
            throw ApiException.validation("forecast_date must be within the next seven local days");
        }
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID id = UUID.randomUUID();
        RunResponse created = repository.insertRun(actor.tenantId(), id, request,
                timezone, cutoff, actor.subject());
        idempotency.remember(actor.tenantId(), "CREATE_FORECAST_RUN", key, request, id);
        audit.succeeded(actor, "FORECAST_RUN_CREATED", "FORECAST_RUN", id, null, null, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(actor.tenantId(), id, actor.subject());
            }
        });
        return created;
    }

    public RunResponse getRun(UUID tenantId, UUID id) {
        return repository.findRun(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("forecast run"));
    }

    public List<RunResponse> listRuns(UUID tenantId, UUID targetId, int limit) {
        return repository.listRuns(tenantId, targetId, limit);
    }

    public RunDetailResponse detail(UUID tenantId, UUID id) {
        RunResponse run = getRun(tenantId, id);
        return new RunDetailResponse(run, repository.latestVersion(tenantId, id).orElse(null));
    }

    public VersionResponse version(UUID tenantId, UUID id) {
        return repository.findVersion(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("forecast version"));
    }

    @Transactional
    public VersionResponse override(ActorPrincipal actor, UUID versionId, String key,
            OverrideRequest request) {
        String operation = "OVERRIDE_FORECAST:" + versionId;
        var replay = idempotency.replay(actor.tenantId(), operation, key, request);
        if (replay.isPresent()) {
            return version(actor.tenantId(), replay.get());
        }
        VersionResponse base = version(actor.tenantId(), versionId);
        repository.lockRun(actor.tenantId(), base.forecastRunId());
        VersionResponse latest = repository.latestVersion(actor.tenantId(), base.forecastRunId())
                .orElseThrow(() -> ApiException.notFound("forecast version"));
        if (!latest.id().equals(base.id())) {
            throw ApiException.conflict("only the latest forecast version can be overridden");
        }
        validateOverride(base, request);
        VersionResponse created = repository.override(actor.tenantId(), base, request, actor.subject());
        idempotency.remember(actor.tenantId(), operation, key, request, created.id());
        audit.succeeded(actor, "FORECAST_OVERRIDDEN", "FORECAST_VERSION", created.id(),
                request.reason(), base, created);
        return created;
    }

    private String resolveTimezone(UUID tenantId, CreateRunRequest request) {
        if ("SITE".equals(request.targetType())) {
            return repository.siteTimezone(tenantId, request.targetId())
                    .orElseThrow(() -> ApiException.notFound("site"));
        }
        if (!repository.portfolioExists(tenantId, request.targetId())) {
            throw ApiException.notFound("portfolio");
        }
        List<String> timezones = repository.portfolioTimezones(tenantId, request.targetId());
        if (timezones.isEmpty()) {
            throw ApiException.validation("portfolio must contain at least one site");
        }
        if (timezones.size() != 1) {
            throw ApiException.validation("portfolio sites must share one timezone for a forecast run");
        }
        return timezones.getFirst();
    }

    private void submit(UUID tenantId, UUID id, String actor) {
        try {
            executor.execute(() -> execute(tenantId, id, actor));
        } catch (RejectedExecutionException exception) {
            finalizeFailure(tenantId, id, "FORECAST_QUEUE_CAPACITY_EXCEEDED");
        }
    }

    private void execute(UUID tenantId, UUID id, String actor) {
        try {
            repository.running(tenantId, id);
            RunResponse run = getRun(tenantId, id);
            var observations = history.query(tenantId, run.targetType(), run.targetId(),
                    run.metric(), run.dataCutoff());
            JsonNode response = client.forecast(run.forecastDate(), run.timezone(), run.metric(),
                    run.dataCutoff(), observations);
            if ("INSUFFICIENT_DATA".equals(response.path("status").asText())) {
                transactions.executeWithoutResult(ignored -> {
                    repository.insufficient(tenantId, id, response);
                    RunResponse completed = getRun(tenantId, id);
                    repository.outbox(tenantId, completed, "FORECAST_INSUFFICIENT_DATA", Instant.now());
                });
                return;
            }
            validateResponse(run, response);
            transactions.executeWithoutResult(ignored -> {
                repository.succeeded(tenantId, id, response, actor);
                RunResponse completed = getRun(tenantId, id);
                repository.outbox(tenantId, completed, "FORECAST_SUCCEEDED", Instant.now());
            });
        } catch (ForecastExecutionException exception) {
            finalizeFailure(tenantId, id, exception.code());
        } catch (RuntimeException exception) {
            finalizeFailure(tenantId, id, "FORECAST_EXECUTION_FAILED");
        }
    }

    private void finalizeFailure(UUID tenantId, UUID id, String code) {
        transactions.executeWithoutResult(ignored -> {
            repository.failed(tenantId, id, code);
            RunResponse failed = getRun(tenantId, id);
            repository.outbox(tenantId, failed, "FORECAST_FAILED", Instant.now());
        });
    }

    private static void validateResponse(RunResponse run, JsonNode response) {
        if (!"SUCCEEDED".equals(response.path("status").asText())) {
            throw new ForecastExecutionException("FORECAST_RESPONSE_INVALID", "unknown forecast status");
        }
        if (!run.dataCutoff().equals(Instant.parse(response.path("data_cutoff").asText()))) {
            throw new ForecastExecutionException("FORECAST_CUTOFF_MISMATCH", "forecast cutoff changed");
        }
        ZoneId zone = ZoneId.of(run.timezone());
        Instant start = run.forecastDate().atStartOfDay(zone).toInstant();
        Instant end = run.forecastDate().plusDays(1).atStartOfDay(zone).toInstant();
        long expected = Duration.between(start, end).toMinutes() / 15;
        JsonNode points = response.path("points");
        if (!points.isArray() || points.size() != expected) {
            throw new ForecastExecutionException("FORECAST_POINT_COVERAGE_INVALID",
                    "forecast does not cover the complete local day");
        }
        Instant cursor = start;
        for (JsonNode point : points) {
            Instant pointStart = Instant.parse(point.path("interval_start").asText());
            Instant pointEnd = Instant.parse(point.path("interval_end").asText());
            double value = point.path("value").asDouble(Double.NaN);
            if (!pointStart.equals(cursor) || !pointEnd.equals(cursor.plus(15, ChronoUnit.MINUTES))
                    || !Double.isFinite(value)
                    || ("PV_POWER_KW".equals(run.metric()) && value < 0)) {
                throw new ForecastExecutionException("FORECAST_POINT_INVALID",
                        "forecast points must be contiguous and valid");
            }
            cursor = pointEnd;
        }
        if (!cursor.equals(end)) {
            throw new ForecastExecutionException("FORECAST_POINT_COVERAGE_INVALID",
                    "forecast does not end at the next local midnight");
        }
    }

    private static void validateOverride(VersionResponse base, OverrideRequest request) {
        Set<Instant> available = base.points().stream().map(PointResponse::intervalStart)
                .collect(java.util.stream.Collectors.toSet());
        Set<Instant> supplied = new HashSet<>();
        for (OverridePoint point : request.points()) {
            if (!supplied.add(point.intervalStart())) {
                throw ApiException.validation("override interval_start values must be unique");
            }
            if (!available.contains(point.intervalStart())) {
                throw ApiException.validation("override interval_start must exist in the base forecast");
            }
            if (point.value().compareTo(BigDecimal.ZERO) < 0) {
                throw ApiException.validation("override values cannot be negative");
            }
        }
    }
}
