package io.vpp.streamprocessor.normalization;

public class TelemetryValidationException extends RuntimeException {
    private final String code;

    public TelemetryValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
