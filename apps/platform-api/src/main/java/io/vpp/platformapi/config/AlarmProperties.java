package io.vpp.platformapi.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.alarms")
public record AlarmProperties(boolean enabled, Duration evaluationInterval,
        Duration resumeTtl, long resumeMaxLength) {
    public AlarmProperties {
        if (evaluationInterval == null || evaluationInterval.isNegative() || evaluationInterval.isZero()) {
            throw new IllegalArgumentException("platform.alarms.evaluation-interval must be positive");
        }
        if (resumeTtl == null || resumeTtl.isNegative() || resumeTtl.isZero()) {
            throw new IllegalArgumentException("platform.alarms.resume-ttl must be positive");
        }
        if (resumeMaxLength < 10 || resumeMaxLength > 100_000) {
            throw new IllegalArgumentException("platform.alarms.resume-max-length must be between 10 and 100000");
        }
    }
}
