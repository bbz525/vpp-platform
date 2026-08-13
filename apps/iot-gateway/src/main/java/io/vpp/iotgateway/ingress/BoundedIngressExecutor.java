package io.vpp.iotgateway.ingress;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vpp.iotgateway.config.GatewayProperties;

@Component
public class BoundedIngressExecutor {
    private final ThreadPoolExecutor executor;
    private final Counter rejected;

    public BoundedIngressExecutor(GatewayProperties properties, MeterRegistry meterRegistry) {
        AtomicInteger sequence = new AtomicInteger();
        int workers = properties.ingress().workerThreads();
        this.executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.ingress().queueCapacity()), runnable -> {
                    Thread thread = Thread.ofPlatform()
                            .name("gateway-ingress-" + sequence.incrementAndGet()).unstarted(runnable);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.rejected = meterRegistry.counter("vpp.gateway.ingress.rejected",
                "reason", "worker_queue_full");
    }

    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            rejected.increment();
            return false;
        }
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
