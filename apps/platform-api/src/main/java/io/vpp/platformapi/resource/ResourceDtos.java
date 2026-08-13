package io.vpp.platformapi.resource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ResourceDtos {
    private ResourceDtos() {
    }

    public record CreatePortfolioRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record PortfolioResponse(UUID id, String name, String status,
            @JsonProperty("created_at") Instant createdAt) {
    }

    public record CreateSiteRequest(
            @NotNull @JsonProperty("portfolio_id") UUID portfolioId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String timezone,
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @JsonProperty("grid_connection_limit_kw") BigDecimal gridConnectionLimitKw) {
    }

    public record SiteResponse(
            UUID id,
            @JsonProperty("portfolio_id") UUID portfolioId,
            String name,
            String timezone,
            @JsonProperty("grid_connection_limit_kw") BigDecimal gridConnectionLimitKw,
            String status,
            @JsonProperty("created_at") Instant createdAt) {
    }

    public record CreateDeviceModelRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "METER|PV_INVERTER|BATTERY|EV_CHARGER") String type,
            @Min(1) @JsonProperty("schema_version") int schemaVersion,
            @NotNull @JsonProperty("point_schema") JsonNode pointSchema) {
    }

    public record DeviceModelResponse(
            UUID id,
            String name,
            String type,
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("point_schema") JsonNode pointSchema,
            String status,
            @JsonProperty("created_at") Instant createdAt) {
    }

    public record DeviceCapabilityInput(
            @NotBlank @Size(max = 80) String capability,
            @NotBlank @Size(max = 24) String unit,
            @NotNull @JsonProperty("min_value") BigDecimal minValue,
            @NotNull @JsonProperty("max_value") BigDecimal maxValue,
            @NotNull @JsonProperty("fallback_value") BigDecimal fallbackValue) {
    }

    public record DeviceCapabilityResponse(
            String capability,
            String unit,
            @JsonProperty("min_value") BigDecimal minValue,
            @JsonProperty("max_value") BigDecimal maxValue,
            @JsonProperty("fallback_value") BigDecimal fallbackValue,
            @JsonProperty("config_version") long configVersion) {
    }

    public record CreateDeviceRequest(
            @NotNull @JsonProperty("site_id") UUID siteId,
            @NotNull @JsonProperty("model_id") UUID modelId,
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
            @JsonProperty("external_code") String externalCode,
            @NotBlank @Size(max = 120) String name,
            @NotNull @Size(max = 32) List<@Valid DeviceCapabilityInput> capabilities) {
    }

    public record DeviceResponse(
            UUID id,
            @JsonProperty("site_id") UUID siteId,
            @JsonProperty("model_id") UUID modelId,
            @JsonProperty("external_code") String externalCode,
            String name,
            String status,
            @JsonProperty("config_version") long configVersion,
            @JsonProperty("credential_configured") boolean credentialConfigured,
            List<DeviceCapabilityResponse> capabilities,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {
    }

    public record UpdateDeviceStatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|MAINTENANCE|DISABLED|SAFETY_LOCKED") String status,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record RotateCredentialRequest(
            @NotBlank @Size(min = 16, max = 512) String credential,
            @NotBlank @Size(max = 500) String reason) {
        @Override
        public String toString() {
            return "RotateCredentialRequest[credential=<redacted>, reason=" + reason + "]";
        }
    }

    public record CredentialReferenceResponse(
            UUID id,
            @JsonProperty("device_id") UUID deviceId,
            String status,
            @JsonProperty("rotated_at") Instant rotatedAt) {
    }

    public record TariffIntervalInput(
            @NotNull Instant start,
            @NotNull Instant end,
            @NotNull @DecimalMin("0") @JsonProperty("price_per_kwh") BigDecimal pricePerKwh) {
    }

    public record CreateTariffPlanRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotBlank @Size(max = 80) String timezone,
            @Min(1) int version,
            @NotNull @JsonProperty("valid_from") Instant validFrom,
            @NotNull @JsonProperty("valid_to") Instant validTo,
            @NotEmpty @Size(max = 10000) List<@Valid TariffIntervalInput> intervals) {
    }

    public record TariffPlanResponse(
            UUID id,
            String name,
            String currency,
            String timezone,
            int version,
            @JsonProperty("valid_from") Instant validFrom,
            @JsonProperty("valid_to") Instant validTo,
            @JsonProperty("interval_count") int intervalCount,
            @JsonProperty("created_at") Instant createdAt) {
    }
}
