package io.vpp.streamprocessor.catalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.vpp.streamprocessor.config.StreamProperties;

@Component
public class DeviceCatalog {
    private static final Logger log = LoggerFactory.getLogger(DeviceCatalog.class);

    private final StreamProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private volatile Map<String, DeviceCatalogEntry> entries = Map.of();
    private volatile Instant lastSuccessfulRefresh;

    @Autowired
    public DeviceCatalog(StreamProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder()
                .connectTimeout(properties.catalog().refreshInterval()).build());
    }

    DeviceCatalog(StreamProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    public Optional<DeviceCatalogEntry> find(String identityKey) {
        return Optional.ofNullable(entries.get(identityKey));
    }

    public int size() {
        return entries.size();
    }

    public boolean isReady() {
        if (!properties.enabled()) return true;
        Instant refreshed = lastSuccessfulRefresh;
        return refreshed != null && refreshed.isAfter(Instant.now()
                .minus(properties.catalog().refreshInterval().multipliedBy(3)));
    }

    public synchronized void refresh() {
        if (!properties.enabled()) return;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.catalog().url() + "/api/v1/internal/device-catalog"))
                    .timeout(properties.catalog().refreshInterval())
                    .header("X-VPP-Internal-Token", properties.catalog().token())
                    .GET().build();
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("device catalog returned HTTP " + response.statusCode());
            }
            DeviceCatalogEntry[] snapshot = mapper.readValue(response.body(), DeviceCatalogEntry[].class);
            Map<String, DeviceCatalogEntry> replacement = Arrays.stream(snapshot).collect(
                    Collectors.toUnmodifiableMap(DeviceCatalogEntry::identityKey,
                            Function.identity(), (left, right) -> {
                                throw new IllegalStateException("duplicate device catalog identity "
                                        + left.identityKey());
                            }));
            entries = replacement;
            lastSuccessfulRefresh = Instant.now();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("device catalog refresh interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("device catalog refresh failed", exception);
        }
    }

    @Scheduled(fixedDelayString = "${stream.catalog.refresh-interval:5s}")
    void scheduledRefresh() {
        if (!properties.enabled()) return;
        try {
            refresh();
        } catch (RuntimeException exception) {
            log.warn("Device catalog refresh failed reason={}", exception.getClass().getSimpleName());
        }
    }
}
