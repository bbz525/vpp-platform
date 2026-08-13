package io.vpp.iotgateway.ingress;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vpp.iotgateway.identity.DeviceAuthorizer;
import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.kafka.GatewayEventPublisher;
import io.vpp.iotgateway.session.DeviceSessionRegistry;

@Component
public class GatewayIngressService implements DeviceIngress {
    private static final Logger log = LoggerFactory.getLogger(GatewayIngressService.class);
    private final BoundedIngressExecutor executor;
    private final DeviceAuthorizer authorizer;
    private final DevicePayloadValidator validator;
    private final GatewayEventPublisher publisher;
    private final DeviceSessionRegistry sessions;
    private final Counter accepted;
    private final Counter rejected;

    public GatewayIngressService(
            BoundedIngressExecutor executor,
            DeviceAuthorizer authorizer,
            DevicePayloadValidator validator,
            GatewayEventPublisher publisher,
            DeviceSessionRegistry sessions,
            MeterRegistry registry) {
        this.executor = executor;
        this.authorizer = authorizer;
        this.validator = validator;
        this.publisher = publisher;
        this.sessions = sessions;
        this.accepted = registry.counter("vpp.gateway.ingress.accepted");
        this.rejected = registry.counter("vpp.gateway.ingress.invalid");
    }

    @Override
    public boolean accept(
            DeviceIdentity identity,
            IngressProtocol protocol,
            IngressMessageType type,
            byte[] payload,
            Consumer<String> rejectionCallback) {
        byte[] ownedPayload = payload.clone();
        boolean submitted = executor.submit(() -> process(identity, protocol, type, ownedPayload,
                rejectionCallback));
        if (!submitted) {
            rejectionCallback.accept("INGRESS_QUEUE_FULL");
        }
        return submitted;
    }

    private void process(
            DeviceIdentity identity,
            IngressProtocol protocol,
            IngressMessageType type,
            byte[] payload,
            Consumer<String> rejectionCallback) {
        try {
            if (!authorizer.isAuthorized(identity)) {
                throw new PayloadValidationException("UNAUTHORIZED_DEVICE");
            }
            boolean sinkAccepted = switch (type) {
                case TELEMETRY -> {
                    validator.validateTelemetry(identity, payload);
                    yield publisher.publishTelemetry(identity, protocol, payload);
                }
                case HEARTBEAT -> {
                    validator.validateHeartbeat(payload);
                    yield true;
                }
                case COMMAND_ACK -> {
                    validator.validateCommandAck(payload);
                    yield publisher.publishCommandAck(identity, protocol, payload);
                }
            };
            if (!sinkAccepted) {
                throw new PayloadValidationException("KAFKA_BACKPRESSURE");
            }
            sessions.seen(identity, protocol);
            accepted.increment();
        } catch (PayloadValidationException exception) {
            rejected.increment();
            log.warn("Rejected {} {} message for device={} reason={}", protocol, type,
                    identity.deviceId(), exception.getMessage());
            rejectionCallback.accept(exception.getMessage());
        } catch (RuntimeException exception) {
            rejected.increment();
            log.warn("Rejected {} {} message for device={} reason=INTERNAL_VALIDATION_ERROR",
                    protocol, type, identity.deviceId());
            rejectionCallback.accept("INTERNAL_VALIDATION_ERROR");
        }
    }
}
