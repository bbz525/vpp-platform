package io.vpp.platformapi.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiError(String code, String message,
        @JsonProperty("trace_id") String traceId, List<Detail> details) {
    public record Detail(String field, String reason) {
    }
}
