package io.vpp.platformapi.forecast;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ForecastHistoryClient {
    List<Observation> query(UUID tenantId, String targetType, UUID targetId,
            String metric, Instant cutoff);
    public record Observation(Instant observedAt, double value, String quality) {}
}
