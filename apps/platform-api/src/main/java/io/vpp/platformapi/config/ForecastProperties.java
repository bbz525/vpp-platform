package io.vpp.platformapi.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.forecast")
public record ForecastProperties(boolean enabled, URI serviceUrl, Duration connectTimeout,
        Duration readTimeout, int minimumHistoryDays, int recentDays,
        double minimumRecentCompleteness, int historyLookbackDays,
        int workerThreads, int queueCapacity) {
    public ForecastProperties {
        if (serviceUrl == null || !java.util.List.of("http", "https").contains(serviceUrl.getScheme())) {
            throw new IllegalArgumentException("platform.forecast.service-url must be HTTP(S)");
        }
        positive(connectTimeout, "platform.forecast.connect-timeout");
        positive(readTimeout, "platform.forecast.read-timeout");
        if (minimumHistoryDays < 7 || minimumHistoryDays > 365) {
            throw new IllegalArgumentException("platform.forecast.minimum-history-days must be between 7 and 365");
        }
        if (recentDays < 1 || recentDays > 30) {
            throw new IllegalArgumentException("platform.forecast.recent-days must be between 1 and 30");
        }
        if (minimumRecentCompleteness < 0 || minimumRecentCompleteness > 1) {
            throw new IllegalArgumentException("platform.forecast.minimum-recent-completeness must be between 0 and 1");
        }
        if (historyLookbackDays < minimumHistoryDays || historyLookbackDays > 365) {
            throw new IllegalArgumentException("platform.forecast.history-lookback-days must cover minimum history");
        }
        if (workerThreads < 1 || workerThreads > 16 || queueCapacity < 1 || queueCapacity > 1_000) {
            throw new IllegalArgumentException("platform.forecast worker bounds are invalid");
        }
    }

    public void validateFor(String environment) {
        if (enabled && "production".equalsIgnoreCase(environment)
                && !"https".equalsIgnoreCase(serviceUrl.getScheme())) {
            throw new IllegalArgumentException("forecast service must use HTTPS in production");
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
