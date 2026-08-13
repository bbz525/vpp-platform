package io.vpp.platformapi.schedule;

import static io.vpp.platformapi.forecast.ForecastDtos.*;
import static io.vpp.platformapi.schedule.OptimizationModels.*;
import static io.vpp.platformapi.schedule.ScheduleDtos.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vpp.platformapi.audit.AuditOutboxWriter;
import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.common.IdempotencyService;
import io.vpp.platformapi.config.ScheduleProperties;
import io.vpp.platformapi.forecast.ForecastRepository;
import io.vpp.platformapi.security.ActorPrincipal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScheduleService {
    private static final Set<String> REQUIRED_CAPABILITIES = Set.of("SET_POWER", "ENERGY_CAPACITY_KWH",
            "SOC_RANGE_PCT", "CHARGE_EFFICIENCY", "DISCHARGE_EFFICIENCY");
    private final ScheduleRepository repository;
    private final ForecastRepository forecasts;
    private final RuleBaselineOptimizer optimizer;
    private final ScheduleConstraintValidator validator;
    private final ScheduleProperties properties;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter audit;
    private final ObjectMapper mapper;

    public ScheduleService(ScheduleRepository repository, ForecastRepository forecasts,
            RuleBaselineOptimizer optimizer, ScheduleConstraintValidator validator,
            ScheduleProperties properties, IdempotencyService idempotency,
            AuditOutboxWriter audit, ObjectMapper mapper) {
        this.repository = repository; this.forecasts = forecasts; this.optimizer = optimizer;
        this.validator = validator; this.properties = properties; this.idempotency = idempotency;
        this.audit = audit; this.mapper = mapper;
    }

    @Transactional
    public ScheduleDetailResponse create(ActorPrincipal actor, String key, CreateScheduleRequest request) {
        var replay = idempotency.replay(actor.tenantId(), "CREATE_SCHEDULE", key, request);
        if (replay.isPresent()) return get(actor.tenantId(), replay.get());
        Context context = context(actor.tenantId(), request);
        UUID scheduleId = UUID.randomUUID();
        Result result;
        Input input = null;
        List<String> preflight = new ArrayList<>();
        List<Battery> batteries = batteries(actor.tenantId(), context, request, preflight);
        if (context.sites().size() != 1) preflight.add("MULTI_SITE_OPTIMIZATION_UNSUPPORTED");
        if (batteries.isEmpty()) preflight.add("NO_SCHEDULABLE_BATTERIES");
        if (preflight.isEmpty()) {
            input = new Input(context.slots(), batteries, context.sites().getFirst().gridLimitKw(),
                    properties.degradationCostPerKwh());
            result = optimizer.optimize(input);
            if (result.feasible()) {
                List<String> failures = validator.validate(input, result);
                if (!failures.isEmpty()) result = new Result(false, failures, List.of(), List.of(), null);
            }
        } else {
            result = new Result(false, preflight.stream().distinct().toList(), List.of(), List.of(), null);
        }

        if (!result.feasible()) {
            repository.insertSchedule(scheduleId, actor.tenantId(), request, context.timezone(), "FAILED",
                    "INFEASIBLE", null, "SCHEDULE_INFEASIBLE", result.reasons(), actor.subject());
        } else {
            UUID snapshotId = UUID.randomUUID();
            JsonNode snapshot = snapshot(context, batteries, request);
            repository.insertSnapshot(snapshotId, actor.tenantId(), request.portfolioId(), snapshot,
                    idempotency.digest(snapshot));
            repository.insertSchedule(scheduleId, actor.tenantId(), request, context.timezone(), "VALIDATED",
                    "FEASIBLE", 1, null, List.of(), actor.subject());
            repository.insertVersion(UUID.randomUUID(), actor.tenantId(), scheduleId, snapshotId, request,
                    result, idempotency.digest(Map.of("input", input, "result", result)), actor.subject());
        }
        idempotency.remember(actor.tenantId(), "CREATE_SCHEDULE", key, request, scheduleId);
        ScheduleDetailResponse created = get(actor.tenantId(), scheduleId);
        audit.succeeded(actor, result.feasible() ? "SCHEDULE_GENERATED" : "SCHEDULE_INFEASIBLE",
                "SCHEDULE", scheduleId, null, request, created);
        repository.outbox(actor.tenantId(), created);
        return created;
    }

    public ScheduleDetailResponse get(UUID tenantId, UUID id) {
        return repository.find(tenantId, id).orElseThrow(() -> ApiException.notFound("schedule"));
    }

    public List<ScheduleResponse> list(UUID tenantId, UUID portfolioId, int limit) {
        return repository.list(tenantId, portfolioId, limit);
    }

    private Context context(UUID tenantId, CreateScheduleRequest request) {
        if (!forecasts.portfolioExists(tenantId, request.portfolioId())) throw ApiException.notFound("portfolio");
        List<ScheduleRepository.SiteConfig> sites = repository.sites(tenantId, request.portfolioId());
        if (sites.isEmpty()) throw ApiException.validation("portfolio must contain at least one site");
        if (sites.stream().anyMatch(site -> !"ACTIVE".equals(site.status()))) {
            throw ApiException.validation("all portfolio sites must be ACTIVE");
        }
        List<String> timezones = sites.stream().map(ScheduleRepository.SiteConfig::timezone).distinct().toList();
        if (timezones.size() != 1) throw ApiException.validation("portfolio sites must share one timezone");
        String timezone = timezones.getFirst();
        LocalDate tomorrow = LocalDate.now(ZoneId.of(timezone)).plusDays(1);
        if (request.scheduleDate().isBefore(tomorrow) || request.scheduleDate().isAfter(tomorrow.plusDays(6))) {
            throw ApiException.validation("schedule_date must be within the next seven local days");
        }
        VersionResponse load = forecast(tenantId, request.loadForecastVersionId(), request, "LOAD_KW", timezone);
        VersionResponse pv = forecast(tenantId, request.pvForecastVersionId(), request, "PV_POWER_KW", timezone);
        ScheduleRepository.TariffConfig tariff = repository.tariff(tenantId, request.tariffPlanId())
                .orElseThrow(() -> ApiException.notFound("tariff plan"));
        if (!timezone.equals(tariff.timezone())) throw ApiException.validation("tariff timezone must match schedule timezone");
        List<Slot> slots = slots(request.scheduleDate(), timezone, load.points(), pv.points(), tariff);
        return new Context(timezone, sites, load, pv, tariff, slots);
    }

    private VersionResponse forecast(UUID tenantId, UUID id, CreateScheduleRequest request,
            String expectedMetric, String timezone) {
        VersionResponse version = forecasts.findVersion(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("forecast version"));
        RunResponse run = forecasts.findRun(tenantId, version.forecastRunId())
                .orElseThrow(() -> ApiException.notFound("forecast run"));
        if (!"SUCCEEDED".equals(run.status()) || !"PORTFOLIO".equals(run.targetType())
                || !run.targetId().equals(request.portfolioId()) || !run.forecastDate().equals(request.scheduleDate())
                || !expectedMetric.equals(run.metric()) || !timezone.equals(run.timezone())) {
            throw ApiException.validation(expectedMetric + " forecast does not match portfolio, date, metric, and timezone");
        }
        return version;
    }

    private List<Slot> slots(LocalDate date, String timezone, List<PointResponse> load,
            List<PointResponse> pv, ScheduleRepository.TariffConfig tariff) {
        Instant dayStart = date.atStartOfDay(ZoneId.of(timezone)).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneId.of(timezone)).toInstant();
        int expected = Math.toIntExact(Duration.between(dayStart, dayEnd).toMinutes() / 15);
        if (load.size() != expected || pv.size() != expected
                || tariff.validFrom().isAfter(dayStart) || tariff.validTo().isBefore(dayEnd)) {
            throw ApiException.validation("forecast and tariff must cover the complete local schedule day");
        }
        List<Slot> slots = new ArrayList<>();
        Instant cursor = dayStart;
        for (int index = 0; index < expected; index++) {
            PointResponse loadPoint = load.get(index); PointResponse pvPoint = pv.get(index);
            Instant end = cursor.plus(Duration.ofMinutes(15));
            if (!loadPoint.intervalStart().equals(cursor) || !loadPoint.intervalEnd().equals(end)
                    || !pvPoint.intervalStart().equals(cursor) || !pvPoint.intervalEnd().equals(end)
                    || loadPoint.value().signum() < 0 || pvPoint.value().signum() < 0) {
                throw ApiException.validation("forecast points must be non-negative, aligned, and contiguous");
            }
            Instant start = cursor;
            BigDecimal price = tariff.intervals().stream()
                    .filter(interval -> !interval.start().isAfter(start) && !interval.end().isBefore(end))
                    .map(ScheduleRepository.TariffPrice::price).findFirst()
                    .orElseThrow(() -> ApiException.validation("tariff intervals must cover every forecast interval"));
            slots.add(new Slot(start, end, loadPoint.value(), pvPoint.value(), price));
            cursor = end;
        }
        return List.copyOf(slots);
    }

    private List<Battery> batteries(UUID tenantId, Context context, CreateScheduleRequest request,
            List<String> failures) {
        List<ScheduleRepository.BatteryConfig> configs = repository.batteries(tenantId, request.portfolioId());
        Map<UUID, BigDecimal> initialSoc = new HashMap<>();
        for (BatteryStateInput state : request.batteryStates()) {
            if (initialSoc.put(state.deviceId(), state.initialSocPct()) != null) {
                failures.add("DUPLICATE_BATTERY_STATE:" + state.deviceId());
            }
        }
        Set<UUID> expectedIds = configs.stream().map(ScheduleRepository.BatteryConfig::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!expectedIds.equals(initialSoc.keySet())) failures.add("BATTERY_STATE_SET_MISMATCH");
        List<Battery> batteries = new ArrayList<>();
        for (ScheduleRepository.BatteryConfig config : configs) {
            Map<String, ScheduleRepository.Capability> capabilities = config.capabilities();
            if (!capabilities.keySet().containsAll(REQUIRED_CAPABILITIES)
                    || capabilities.values().stream().anyMatch(cap -> cap.configVersion() != config.configVersion())) {
                failures.add("BATTERY_CAPABILITY_SNAPSHOT_INVALID:" + config.id()); continue;
            }
            var power = capabilities.get("SET_POWER");
            var capacity = capabilities.get("ENERGY_CAPACITY_KWH");
            var soc = capabilities.get("SOC_RANGE_PCT");
            var chargeEfficiency = capabilities.get("CHARGE_EFFICIENCY");
            var dischargeEfficiency = capabilities.get("DISCHARGE_EFFICIENCY");
            BigDecimal minSoc = soc.min().add(properties.socSafetyMarginPct());
            BigDecimal maxSoc = soc.max().subtract(properties.socSafetyMarginPct());
            boolean invalid = !"kW".equals(power.unit()) || power.min().signum() >= 0 || power.max().signum() <= 0
                    || !"kWh".equals(capacity.unit()) || capacity.fallback().signum() <= 0
                    || !"%".equals(soc.unit()) || minSoc.compareTo(maxSoc) >= 0
                    || !"ratio".equals(chargeEfficiency.unit()) || !validEfficiency(chargeEfficiency.fallback())
                    || !"ratio".equals(dischargeEfficiency.unit()) || !validEfficiency(dischargeEfficiency.fallback());
            if (invalid) {
                failures.add("BATTERY_CAPABILITY_VALUE_INVALID:" + config.id()); continue;
            }
            BigDecimal initial = initialSoc.get(config.id());
            if (initial == null || initial.compareTo(minSoc) < 0 || initial.compareTo(maxSoc) > 0) {
                failures.add("INITIAL_SOC_OUTSIDE_SAFE_RANGE:" + config.id()); continue;
            }
            batteries.add(new Battery(config.id(), config.siteId(), config.configVersion(), capacity.fallback(),
                    power.min().abs(), power.max(), minSoc, maxSoc, chargeEfficiency.fallback(),
                    dischargeEfficiency.fallback(), initial));
        }
        return List.copyOf(batteries);
    }

    private boolean validEfficiency(BigDecimal value) {
        return value.signum() > 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }

    private JsonNode snapshot(Context context, List<Battery> batteries, CreateScheduleRequest request) {
        return mapper.valueToTree(Map.of("portfolio_id", request.portfolioId(), "timezone", context.timezone(),
                "sites", context.sites(), "batteries", batteries,
                "soc_safety_margin_pct", properties.socSafetyMarginPct()));
    }

    private record Context(String timezone, List<ScheduleRepository.SiteConfig> sites,
            VersionResponse load, VersionResponse pv, ScheduleRepository.TariffConfig tariff,
            List<Slot> slots) {}
}
