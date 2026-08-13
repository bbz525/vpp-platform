package io.vpp.streamprocessor.catalog;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

public record DeviceCatalogEntry(
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("device_id") UUID deviceId,
        @JsonProperty("external_code") String externalCode,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("portfolio_id") UUID portfolioId,
        @JsonProperty("device_type") String deviceType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("point_schema") JsonNode pointSchema,
        @JsonProperty("device_status") String deviceStatus,
        @JsonProperty("config_version") long configVersion) {

    public String identityKey() {
        return tenantId + ":" + externalCode;
    }
}
