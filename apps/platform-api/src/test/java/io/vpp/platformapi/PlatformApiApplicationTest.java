package io.vpp.platformapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.6-alpine:///vpp",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "platform.outbox.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformApiApplicationTest {
    @Test
    void contextLoads() {
    }
}
