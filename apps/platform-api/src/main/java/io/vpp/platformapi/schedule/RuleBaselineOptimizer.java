package io.vpp.platformapi.schedule;

import static io.vpp.platformapi.schedule.OptimizationModels.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

@Component
final class RuleBaselineOptimizer {
    static final String ALGORITHM_NAME = "RULE_BASELINE";
    static final String ALGORITHM_VERSION = "1.0.0";
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal EPSILON = new BigDecimal("0.000001");

    Result optimize(Input input) {
        if (input.slots().isEmpty()) return Result.infeasible("FORECAST_POINTS_EMPTY");
        if (input.batteries().isEmpty()) return Result.infeasible("NO_SCHEDULABLE_BATTERIES");

        List<BigDecimal> prices = input.slots().stream().map(Slot::pricePerKwh).distinct().sorted().toList();
        BigDecimal lowPrice = prices.get((prices.size() - 1) / 4);
        BigDecimal highPrice = prices.get(((prices.size() - 1) * 3 + 3) / 4);
        boolean hasSpread = highPrice.compareTo(lowPrice) > 0;
        List<Battery> batteries = input.batteries().stream()
                .sorted(Comparator.comparing(battery -> battery.deviceId().toString())).toList();
        Map<java.util.UUID, BigDecimal> energy = new HashMap<>();
        for (Battery battery : batteries) {
            energy.put(battery.deviceId(), energyAtSoc(battery, battery.initialSocPct()));
        }

        List<IntervalPlan> intervals = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        BigDecimal totalBaselineCost = BigDecimal.ZERO;
        BigDecimal totalPlannedCost = BigDecimal.ZERO;
        BigDecimal totalThroughput = BigDecimal.ZERO;
        BigDecimal baselinePeak = BigDecimal.ZERO;
        BigDecimal plannedPeak = BigDecimal.ZERO;

        for (Slot slot : input.slots()) {
            BigDecimal hours = hours(slot);
            BigDecimal baseline = slot.loadKw().subtract(slot.pvKw(), MC);
            Map<java.util.UUID, MutableDispatch> dispatches = new HashMap<>();
            for (Battery battery : batteries) dispatches.put(battery.deviceId(), new MutableDispatch());
            BigDecimal planned = baseline;

            if (planned.compareTo(input.gridLimitKw()) > 0) {
                BigDecimal needed = planned.subtract(input.gridLimitKw(), MC);
                needed = allocateDischarge(batteries, energy, dispatches, needed, hours, battery -> true);
                planned = planned.subtract(sumDischarge(dispatches), MC);
                if (needed.compareTo(EPSILON) > 0) return Result.infeasible("GRID_IMPORT_LIMIT_UNSATISFIABLE");
            } else if (planned.compareTo(input.gridLimitKw().negate()) < 0) {
                BigDecimal needed = input.gridLimitKw().negate().subtract(planned, MC);
                needed = allocateCharge(batteries, energy, dispatches, needed, hours, battery -> true);
                planned = planned.add(sumCharge(dispatches), MC);
                if (needed.compareTo(EPSILON) > 0) return Result.infeasible("GRID_EXPORT_LIMIT_UNSATISFIABLE");
            }

            if (hasSpread && slot.pricePerKwh().compareTo(lowPrice) <= 0) {
                BigDecimal headroom = input.gridLimitKw().subtract(planned, MC).max(BigDecimal.ZERO);
                allocateCharge(batteries, energy, dispatches, headroom, hours,
                        battery -> highPrice.multiply(battery.chargeEfficiency(), MC)
                                .multiply(battery.dischargeEfficiency(), MC)
                                .subtract(slot.pricePerKwh(), MC)
                                .compareTo(input.degradationCostPerKwh().multiply(BigDecimal.valueOf(2), MC)) > 0);
            } else if (hasSpread && slot.pricePerKwh().compareTo(highPrice) >= 0) {
                BigDecimal headroom = planned.add(input.gridLimitKw(), MC).max(BigDecimal.ZERO);
                allocateDischarge(batteries, energy, dispatches, headroom, hours,
                        battery -> slot.pricePerKwh().compareTo(input.degradationCostPerKwh()) > 0);
            }

            BigDecimal charge = sumCharge(dispatches);
            BigDecimal discharge = sumDischarge(dispatches);
            planned = baseline.add(charge, MC).subtract(discharge, MC);
            BigDecimal baselineCost = importCost(baseline, slot.pricePerKwh(), hours);
            BigDecimal plannedCost = importCost(planned, slot.pricePerKwh(), hours);
            intervals.add(new IntervalPlan(slot.start(), slot.end(), scale(slot.loadKw()), scale(slot.pvKw()),
                    scale(slot.pricePerKwh()), scale(baseline), scale(planned), scale(baselineCost),
                    scale(plannedCost)));
            totalBaselineCost = totalBaselineCost.add(baselineCost, MC);
            totalPlannedCost = totalPlannedCost.add(plannedCost, MC);
            totalThroughput = totalThroughput.add(charge.add(discharge, MC).multiply(hours, MC), MC);
            baselinePeak = baselinePeak.max(baseline.max(BigDecimal.ZERO));
            plannedPeak = plannedPeak.max(planned.max(BigDecimal.ZERO));

            for (Battery battery : batteries) {
                MutableDispatch dispatch = dispatches.get(battery.deviceId());
                BigDecimal endEnergy = energy.get(battery.deviceId());
                BigDecimal startEnergy = endEnergy
                        .subtract(dispatch.charge.multiply(hours, MC).multiply(battery.chargeEfficiency(), MC), MC)
                        .add(dispatch.discharge.multiply(hours, MC).divide(battery.dischargeEfficiency(), MC), MC);
                targets.add(new Target(battery.deviceId(), slot.start(), slot.end(), scale(dispatch.charge),
                        scale(dispatch.discharge), scale(dispatch.discharge.subtract(dispatch.charge, MC)),
                        scale(socAtEnergy(battery, startEnergy)), scale(socAtEnergy(battery, endEnergy))));
            }
        }

        BigDecimal degradation = totalThroughput.multiply(input.degradationCostPerKwh(), MC);
        BigDecimal objective = totalPlannedCost.add(degradation, MC);
        BigDecimal capacity = batteries.stream().map(Battery::capacityKwh).reduce(BigDecimal.ZERO,
                (left, right) -> left.add(right, MC));
        BigDecimal cycles = capacity.signum() == 0 ? BigDecimal.ZERO
                : totalThroughput.divide(capacity.multiply(BigDecimal.valueOf(2), MC), MC);
        Summary summary = new Summary(scale(totalBaselineCost), scale(totalPlannedCost), scale(degradation),
                scale(objective), scale(totalBaselineCost.subtract(objective, MC)), scale(baselinePeak),
                scale(plannedPeak), scale(totalThroughput), scale(cycles));
        return new Result(true, List.of(), List.copyOf(intervals), List.copyOf(targets), summary);
    }

    private BigDecimal allocateCharge(List<Battery> batteries, Map<java.util.UUID, BigDecimal> energy,
            Map<java.util.UUID, MutableDispatch> dispatches, BigDecimal requestedKw, BigDecimal hours,
            Predicate<Battery> allowed) {
        BigDecimal remaining = requestedKw.max(BigDecimal.ZERO);
        for (Battery battery : batteries) {
            MutableDispatch dispatch = dispatches.get(battery.deviceId());
            if (remaining.compareTo(EPSILON) <= 0 || dispatch.discharge.signum() > 0) continue;
            BigDecimal room = energyAtSoc(battery, battery.maxSocPct()).subtract(energy.get(battery.deviceId()), MC);
            BigDecimal energyLimitedKw = room.divide(hours.multiply(battery.chargeEfficiency(), MC), MC)
                    .max(BigDecimal.ZERO);
            BigDecimal power = remaining.min(battery.maxChargeKw().subtract(dispatch.charge, MC))
                    .min(energyLimitedKw).max(BigDecimal.ZERO);
            if (!allowed.test(battery)) continue;
            dispatch.charge = dispatch.charge.add(power, MC);
            energy.put(battery.deviceId(), energy.get(battery.deviceId())
                    .add(power.multiply(hours, MC).multiply(battery.chargeEfficiency(), MC), MC));
            remaining = remaining.subtract(power, MC);
        }
        return remaining.max(BigDecimal.ZERO);
    }

    private BigDecimal allocateDischarge(List<Battery> batteries, Map<java.util.UUID, BigDecimal> energy,
            Map<java.util.UUID, MutableDispatch> dispatches, BigDecimal requestedKw, BigDecimal hours,
            Predicate<Battery> allowed) {
        BigDecimal remaining = requestedKw.max(BigDecimal.ZERO);
        for (Battery battery : batteries) {
            MutableDispatch dispatch = dispatches.get(battery.deviceId());
            if (remaining.compareTo(EPSILON) <= 0 || dispatch.charge.signum() > 0) continue;
            BigDecimal available = energy.get(battery.deviceId()).subtract(energyAtSoc(battery, battery.minSocPct()), MC);
            BigDecimal energyLimitedKw = available.multiply(battery.dischargeEfficiency(), MC).divide(hours, MC)
                    .max(BigDecimal.ZERO);
            BigDecimal power = remaining.min(battery.maxDischargeKw().subtract(dispatch.discharge, MC))
                    .min(energyLimitedKw).max(BigDecimal.ZERO);
            if (!allowed.test(battery) || power.signum() == 0) continue;
            dispatch.discharge = dispatch.discharge.add(power, MC);
            energy.put(battery.deviceId(), energy.get(battery.deviceId())
                    .subtract(power.multiply(hours, MC).divide(battery.dischargeEfficiency(), MC), MC));
            remaining = remaining.subtract(power, MC);
        }
        return remaining.max(BigDecimal.ZERO);
    }

    private BigDecimal sumCharge(Map<java.util.UUID, MutableDispatch> values) {
        return values.values().stream().map(value -> value.charge).reduce(BigDecimal.ZERO,
                (left, right) -> left.add(right, MC));
    }

    private BigDecimal sumDischarge(Map<java.util.UUID, MutableDispatch> values) {
        return values.values().stream().map(value -> value.discharge).reduce(BigDecimal.ZERO,
                (left, right) -> left.add(right, MC));
    }

    private BigDecimal energyAtSoc(Battery battery, BigDecimal socPct) {
        return battery.capacityKwh().multiply(socPct, MC).divide(ONE_HUNDRED, MC);
    }

    private BigDecimal socAtEnergy(Battery battery, BigDecimal energyKwh) {
        return energyKwh.multiply(ONE_HUNDRED, MC).divide(battery.capacityKwh(), MC);
    }

    private BigDecimal hours(Slot slot) {
        return BigDecimal.valueOf(Duration.between(slot.start(), slot.end()).toMillis())
                .divide(BigDecimal.valueOf(3_600_000), MC);
    }

    private BigDecimal importCost(BigDecimal gridKw, BigDecimal price, BigDecimal hours) {
        return gridKw.max(BigDecimal.ZERO).multiply(price, MC).multiply(hours, MC);
    }

    static BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static final class MutableDispatch {
        private BigDecimal charge = BigDecimal.ZERO;
        private BigDecimal discharge = BigDecimal.ZERO;
    }
}
