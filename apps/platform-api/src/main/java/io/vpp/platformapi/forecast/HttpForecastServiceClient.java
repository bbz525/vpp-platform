package io.vpp.platformapi.forecast;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.config.ForecastProperties;
import io.vpp.platformapi.forecast.ForecastHistoryClient.Observation;

@Component
public class HttpForecastServiceClient implements ForecastServiceClient {
    private final ForecastProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpForecastServiceClient(ForecastProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    @Override
    public JsonNode forecast(LocalDate date, String timezone, String metric, Instant cutoff,
            List<Observation> observations) {
        var body = mapper.createObjectNode();
        body.put("forecast_date", date.toString());
        body.put("timezone", timezone);
        body.put("metric", metric);
        body.put("data_cutoff", cutoff.toString());
        body.put("minimum_history_days", properties.minimumHistoryDays());
        body.put("recent_days", properties.recentDays());
        body.put("minimum_recent_completeness", properties.minimumRecentCompleteness());
        var items = body.putArray("observations");
        for (Observation observation : observations) {
            var item = items.addObject();
            item.put("observed_at", observation.observedAt().toString());
            item.put("value", observation.value());
            item.put("quality", observation.quality());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(properties.serviceUrl().resolve("/v1/forecasts"))
                    .timeout(properties.readTimeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ForecastExecutionException("FORECAST_SERVICE_REJECTED",
                        "forecast service returned HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ForecastExecutionException("FORECAST_SERVICE_INTERRUPTED",
                    "forecast request was interrupted", exception);
        } catch (IOException | JacksonException exception) {
            throw new ForecastExecutionException("FORECAST_SERVICE_UNAVAILABLE",
                    "forecast service is temporarily unavailable", exception);
        }
    }
}
