package io.vpp.platformapi.forecast;

final class ForecastExecutionException extends RuntimeException {
    private final String code;

    ForecastExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    ForecastExecutionException(String code, String message) {
        this(code, message, null);
    }

    String code() {
        return code;
    }
}
