package io.vpp.platformapi.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String object) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", object + " not found");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "actor lacks required permission");
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "authentication required");
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "REALTIME_DEPENDENCY_UNAVAILABLE", message);
    }

    public static ApiException forecastUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "FORECAST_DEPENDENCY_UNAVAILABLE", message);
    }
}
