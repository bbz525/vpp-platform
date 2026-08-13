package io.vpp.platformapi.forecast;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.vpp.platformapi.forecast.ForecastHistoryClient.Observation;

public interface ForecastServiceClient {
    JsonNode forecast(LocalDate date, String timezone, String metric, Instant cutoff,
            List<Observation> observations);
}
