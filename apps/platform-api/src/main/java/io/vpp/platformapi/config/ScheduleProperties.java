package io.vpp.platformapi.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.schedule")
public record ScheduleProperties(BigDecimal socSafetyMarginPct,
        BigDecimal degradationCostPerKwh) {
    public ScheduleProperties {
        if (socSafetyMarginPct == null || socSafetyMarginPct.signum() < 0
                || socSafetyMarginPct.compareTo(BigDecimal.valueOf(20)) > 0) {
            throw new IllegalArgumentException("platform.schedule.soc-safety-margin-pct must be between 0 and 20");
        }
        if (degradationCostPerKwh == null || degradationCostPerKwh.signum() < 0) {
            throw new IllegalArgumentException("platform.schedule.degradation-cost-per-kwh cannot be negative");
        }
    }
}
