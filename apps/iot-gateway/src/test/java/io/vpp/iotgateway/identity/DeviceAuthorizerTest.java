package io.vpp.iotgateway.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.vpp.iotgateway.config.GatewayProperties;
import io.vpp.iotgateway.support.TestGatewayProperties;

class DeviceAuthorizerTest {
    @Test
    void refreshesActiveIdentitySnapshotWithoutHttpOnAuthenticationPath() throws Exception {
        String token = "local-dev-platform-internal-token-only";
        String credential = "remote-device-secret";
        AtomicReference<String> body = new AtomicReference<>("""
                [{"tenant_id":"7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941","device_id":"bess-001",
                  "credential_verifier":"%s","config_version":2}]
                """.formatted(verifier(token, credential)));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/device-identities", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-VPP-Internal-Token")).isEqualTo(token);
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DeviceAuthorizer authorizer = new DeviceAuthorizer(remoteProperties(server.getAddress().getPort(), token));
            authorizer.run(null);
            DeviceIdentity identity = new DeviceIdentity(TestGatewayProperties.TENANT_ID, "bess-001");
            assertThat(authorizer.authenticate(identity, credential)).contains(identity);
            assertThat(authorizer.authenticate(identity, "wrong-credential")).isEmpty();

            body.set("[]");
            authorizer.scheduledRefresh();
            assertThat(authorizer.isAuthorized(identity)).isFalse();
        } finally {
            server.stop(0);
        }
    }

    private static GatewayProperties remoteProperties(int port, String token) {
        GatewayProperties defaults = TestGatewayProperties.defaults();
        return new GatewayProperties("local", defaults.mqtt(), defaults.tcp(), defaults.ingress(),
                defaults.commands(), defaults.kafka(), new GatewayProperties.Identity(false,
                        "http://127.0.0.1:" + port, token, Duration.ofSeconds(2), List.of()));
    }

    private static String verifier(String token, String credential) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "hmac-sha256$" + Base64.getEncoder().encodeToString(
                mac.doFinal(credential.getBytes(StandardCharsets.UTF_8)));
    }
}
