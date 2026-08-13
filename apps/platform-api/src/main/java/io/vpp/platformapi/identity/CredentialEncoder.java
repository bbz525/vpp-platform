package io.vpp.platformapi.identity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import io.vpp.platformapi.config.PlatformProperties;

@Component
public class CredentialEncoder {
    private final byte[] pepper;

    public CredentialEncoder(PlatformProperties properties) {
        this.pepper = properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    public String encode(String credential) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return "hmac-sha256$" + Base64.getEncoder().encodeToString(
                    mac.doFinal(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}
