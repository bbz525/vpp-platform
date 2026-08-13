package io.vpp.streamprocessor.runtime;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import io.vpp.streamprocessor.catalog.DeviceCatalog;
import io.vpp.streamprocessor.config.StreamProperties;
import io.vpp.streamprocessor.sink.ClickHouseTelemetryStore;

@Component
public class StreamBootstrap implements ApplicationRunner {
    private final StreamProperties properties;
    private final ClickHouseTelemetryStore clickHouse;
    private final DeviceCatalog catalog;
    private final KafkaListenerEndpointRegistry listeners;

    public StreamBootstrap(StreamProperties properties, ClickHouseTelemetryStore clickHouse,
            DeviceCatalog catalog, KafkaListenerEndpointRegistry listeners) {
        this.properties = properties;
        this.clickHouse = clickHouse;
        this.catalog = catalog;
        this.listeners = listeners;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) return;
        clickHouse.initializeSchema();
        catalog.refresh();
        var container = listeners.getListenerContainer("telemetryNormalizer");
        if (container == null) throw new IllegalStateException("telemetry listener is not registered");
        container.start();
    }
}
