package io.vpp.platformapi.schedule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class OptimizationModels {
    private OptimizationModels() {}

    record Slot(Instant start, Instant end, BigDecimal loadKw, BigDecimal pvKw,
            BigDecimal pricePerKwh) {}

    record Battery(UUID deviceId, UUID siteId, long configVersion, BigDecimal capacityKwh,
            BigDecimal maxChargeKw, BigDecimal maxDischargeKw, BigDecimal minSocPct,
            BigDecimal maxSocPct, BigDecimal chargeEfficiency, BigDecimal dischargeEfficiency,
            BigDecimal initialSocPct) {}

    record Input(List<Slot> slots, List<Battery> batteries, BigDecimal gridLimitKw,
            BigDecimal degradationCostPerKwh) {}

    record IntervalPlan(Instant start, Instant end, BigDecimal loadKw, BigDecimal pvKw,
            BigDecimal pricePerKwh, BigDecimal baselineGridKw, BigDecimal plannedGridKw,
            BigDecimal baselineCost, BigDecimal plannedCost) {}

    record Target(UUID deviceId, Instant start, Instant end, BigDecimal chargeKw,
            BigDecimal dischargeKw, BigDecimal setpointKw, BigDecimal socStartPct,
            BigDecimal socEndPct) {}

    record Summary(BigDecimal baselineCost, BigDecimal plannedEnergyCost,
            BigDecimal degradationCost, BigDecimal objectiveCost, BigDecimal savings,
            BigDecimal baselinePeakKw, BigDecimal plannedPeakKw, BigDecimal throughputKwh,
            BigDecimal equivalentCycles) {}

    record Result(boolean feasible, List<String> reasons, List<IntervalPlan> intervals,
            List<Target> targets, Summary summary) {
        static Result infeasible(String reason) {
            return new Result(false, List.of(reason), List.of(), List.of(), null);
        }
    }
}
