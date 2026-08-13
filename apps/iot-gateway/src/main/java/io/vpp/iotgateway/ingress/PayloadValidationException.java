package io.vpp.iotgateway.ingress;

public class PayloadValidationException extends RuntimeException {
    public PayloadValidationException(String reasonCode) {
        super(reasonCode);
    }

    public PayloadValidationException(String reasonCode, Throwable cause) {
        super(reasonCode, cause);
    }
}
