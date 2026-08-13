package io.vpp.iotgateway.tcp;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.PayloadValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TcpAuthenticationParser {
    private static final Set<String> FIELDS = Set.of(
            "device_id", "tenant_id", "credential", "client_nonce");
    private final ObjectMapper objectMapper;

    public TcpAuthenticationParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationAttempt parse(byte[] payload) {
        if (payload.length == 0 || payload.length > 4096) {
            throw new PayloadValidationException("AUTH_PAYLOAD_SIZE_INVALID");
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject() || !FIELDS.containsAll(root.propertyNames())) {
                throw new PayloadValidationException("AUTH_PAYLOAD_INVALID");
            }
            String deviceId = requiredText(root, "device_id", 1, 128);
            UUID tenantId = UUID.fromString(requiredText(root, "tenant_id", 36, 36));
            String credential = requiredText(root, "credential", 1, 512);
            UUID.fromString(requiredText(root, "client_nonce", 36, 36));
            return new AuthenticationAttempt(new DeviceIdentity(tenantId, deviceId), credential);
        } catch (PayloadValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PayloadValidationException("AUTH_PAYLOAD_INVALID", exception);
        }
    }

    private static String requiredText(JsonNode root, String name, int min, int max) {
        JsonNode value = root.get(name);
        if (value == null || !value.isString()) {
            throw new PayloadValidationException("AUTH_PAYLOAD_INVALID");
        }
        String text = value.asString();
        if (text.length() < min || text.length() > max) {
            throw new PayloadValidationException("AUTH_PAYLOAD_INVALID");
        }
        return text;
    }
}
