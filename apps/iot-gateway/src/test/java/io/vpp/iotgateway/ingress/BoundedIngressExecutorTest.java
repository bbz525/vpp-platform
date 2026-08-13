package io.vpp.iotgateway.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vpp.iotgateway.support.TestGatewayProperties;

class BoundedIngressExecutorTest {

    @Test
    void rejectsWhenWorkersAndQueueAreBothFull() throws Exception {
        var executor = new BoundedIngressExecutor(
                TestGatewayProperties.withIngress(1, 1, 1), new SimpleMeterRegistry());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThat(executor.submit(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            })).isTrue();
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> { })).isTrue();
            assertThat(executor.submit(() -> { })).isFalse();
        } finally {
            release.countDown();
            executor.close();
        }
    }
}
