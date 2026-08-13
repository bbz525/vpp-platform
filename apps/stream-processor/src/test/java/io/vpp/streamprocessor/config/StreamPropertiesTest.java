package io.vpp.streamprocessor.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.vpp.streamprocessor.support.TestStreamProperties;

class StreamPropertiesTest {
    @Test
    void rejectsInsecureProductionCatalogUrl() {
        StreamProperties local = TestStreamProperties.create();
        assertThatThrownBy(() -> new StreamProperties(true, "production", false, local.catalog(),
                local.clickhouse(), local.kafka(), local.quality(), local.state(), local.sinkTimeout()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
