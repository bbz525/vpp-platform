package io.vpp.platformapi.resource;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vpp.platformapi.audit.AuditOutboxWriter;
import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.common.IdempotencyService;
import io.vpp.platformapi.resource.ResourceDtos.CreateTariffPlanRequest;
import io.vpp.platformapi.resource.ResourceDtos.TariffIntervalInput;
import io.vpp.platformapi.resource.ResourceDtos.TariffPlanResponse;
import io.vpp.platformapi.security.ActorPrincipal;

@Service
public class TariffService {
    private final ResourceRepository repository;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter audit;

    public TariffService(ResourceRepository repository, IdempotencyService idempotency,
            AuditOutboxWriter audit) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional
    public TariffPlanResponse create(ActorPrincipal actor, String key,
            CreateTariffPlanRequest request) {
        validate(request);
        var replay = idempotency.replay(actor.tenantId(), "CREATE_TARIFF_PLAN", key, request);
        if (replay.isPresent()) {
            return repository.findTariff(actor.tenantId(), replay.get())
                    .orElseThrow(() -> ApiException.notFound("tariff plan"));
        }
        UUID id = UUID.randomUUID();
        TariffPlanResponse created = repository.insertTariff(actor.tenantId(), id, request.name().trim(),
                request.currency(), request.timezone(), request.version(), request.validFrom(),
                request.validTo(), request.intervals());
        idempotency.remember(actor.tenantId(), "CREATE_TARIFF_PLAN", key, request, id);
        audit.succeeded(actor, "TARIFF_PLAN_CREATED", "TARIFF_PLAN", id, null, null, created);
        return created;
    }

    public List<TariffPlanResponse> list(UUID tenantId) {
        return repository.listTariffs(tenantId);
    }

    static void validate(CreateTariffPlanRequest request) {
        try {
            ZoneId.of(request.timezone());
        } catch (Exception exception) {
            throw ApiException.validation("timezone must be a valid IANA time zone");
        }
        if (!request.validTo().isAfter(request.validFrom())) {
            throw ApiException.validation("valid_to must be after valid_from");
        }
        List<TariffIntervalInput> sorted = request.intervals().stream()
                .sorted(Comparator.comparing(TariffIntervalInput::start)).toList();
        TariffIntervalInput previous = null;
        for (TariffIntervalInput interval : sorted) {
            if (!interval.end().isAfter(interval.start())) {
                throw ApiException.validation("tariff interval end must be after start");
            }
            if (interval.start().isBefore(request.validFrom()) || interval.end().isAfter(request.validTo())) {
                throw ApiException.validation("tariff interval must be inside plan validity");
            }
            if (previous != null && interval.start().isBefore(previous.end())) {
                throw ApiException.validation("tariff intervals cannot overlap");
            }
            previous = interval;
        }
    }
}
