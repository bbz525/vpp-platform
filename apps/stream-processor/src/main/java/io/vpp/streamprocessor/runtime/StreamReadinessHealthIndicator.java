package io.vpp.streamprocessor.runtime;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import io.vpp.streamprocessor.catalog.DeviceCatalog;
import io.vpp.streamprocessor.config.StreamProperties;

@Component("streamPipeline")
public class StreamReadinessHealthIndicator implements HealthIndicator {
    private final StreamProperties properties;
    private final DeviceCatalog catalog;
    private final KafkaListenerEndpointRegistry listeners;

    public StreamReadinessHealthIndicator(StreamProperties properties, DeviceCatalog catalog,
            KafkaListenerEndpointRegistry listeners) {
        this.properties = properties;
        this.catalog = catalog;
        this.listeners = listeners;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) return Health.up().withDetail("enabled", false).build();
        var listener = listeners.getListenerContainer("telemetryNormalizer");
        boolean running = listener != null && listener.isRunning();
        if (running && catalog.isReady()) {
            return Health.up().withDetail("catalogDevices", catalog.size()).build();
        }
        return Health.down().withDetail("listenerRunning", running)
                .withDetail("catalogReady", catalog.isReady()).build();
    }
}
