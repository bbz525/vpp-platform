package io.vpp.streamprocessor.normalization;

import java.util.Map;

import io.vpp.streamprocessor.catalog.DeviceCatalogEntry;

public record NormalizedResult(NormalizedTelemetry telemetry, DeviceCatalogEntry catalog,
        Map<String, String> units) {
}
