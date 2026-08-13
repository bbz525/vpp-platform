package io.vpp.platformapi.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

import io.vpp.platformapi.common.ApiException;
import io.vpp.platformapi.config.PlatformProperties;

@Component
public final class InternalTokenGuard {
    private final byte[] expectedToken;

    public InternalTokenGuard(PlatformProperties properties) {
        this.expectedToken = properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String presentedToken) {
        byte[] presented = presentedToken == null ? new byte[0]
                : presentedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, presented)) {
            throw ApiException.unauthorized();
        }
    }
}
