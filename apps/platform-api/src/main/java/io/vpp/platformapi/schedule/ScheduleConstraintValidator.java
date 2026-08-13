package io.vpp.platformapi.schedule;

import static io.vpp.platformapi.schedule.OptimizationModels.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
final class ScheduleConstraintValidator {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal EPSILON = new BigDecimal("0.0001");

    List<String> validate(Input input, Result result) {
        List<String> failures = new ArrayList<>();
        if (!result.feasible()) return result.reasons();
        if (result.intervals().size() != input.slots().size()
                || result.targets().size() != input.slots().size() * input.batteries().size()) {
            return List.of("OPTIMIZER_OUTPUT_CARDINALITY_INVALID");
        }
        Map<java.util.UUID, Battery> batteries = new HashMap<>();
        Map<java.util.UUID, BigDecimal> expectedSoc = new HashMap<>();
        input.batteries().forEach(battery -> {
            batteries.put(battery.deviceId(), battery);
            expectedSoc.put(battery.deviceId(), battery.initialSocPct());
        });
        Map<InstantDevice, Target> targets = new HashMap<>();
        for (Target target : result.targets()) {
            if (targets.put(new InstantDevice(target.start(), target.deviceId()), target) != null) {
                failures.add("DUPLICATE_DEVICE_INTERVAL_TARGET");
            }
        }
        for (int index = 0; index < input.slots().size(); index++) {
            Slot slot = input.slots().get(index);
            IntervalPlan interval = result.intervals().get(index);
            if (!slot.start().equals(interval.start()) || !slot.end().equals(interval.end())) {
                failures.add("INTERVAL_ALIGNMENT_INVALID"); continue;
            }
            BigDecimal totalCharge = BigDecimal.ZERO;
            BigDecimal totalDischarge = BigDecimal.ZERO;
            BigDecimal hours = BigDecimal.valueOf(Duration.between(slot.start(), slot.end()).toMillis())
                    .divide(BigDecimal.valueOf(3_600_000), MC);
            for (Battery battery : input.batteries()) {
                Target target = targets.get(new InstantDevice(slot.start(), battery.deviceId()));
                if (target == null) { failures.add("DEVICE_INTERVAL_TARGET_MISSING"); continue; }
                if (target.chargeKw().signum() < 0 || target.dischargeKw().signum() < 0
                        || target.chargeKw().signum() > 0 && target.dischargeKw().signum() > 0) {
                    failures.add("CHARGE_DISCHARGE_EXCLUSIVITY_VIOLATED");
                }
                if (target.chargeKw().compareTo(battery.maxChargeKw().add(EPSILON)) > 0
                        || target.dischargeKw().compareTo(battery.maxDischargeKw().add(EPSILON)) > 0) {
                    failures.add("BATTERY_POWER_LIMIT_VIOLATED");
                }
                if (target.socStartPct().subtract(expectedSoc.get(battery.deviceId())).abs().compareTo(EPSILON) > 0) {
                    failures.add("SOC_CONTINUITY_VIOLATED");
                }
                BigDecimal startEnergy = battery.capacityKwh().multiply(target.socStartPct(), MC)
                        .divide(BigDecimal.valueOf(100), MC);
                BigDecimal endEnergy = startEnergy
                        .add(target.chargeKw().multiply(hours, MC).multiply(battery.chargeEfficiency(), MC), MC)
                        .subtract(target.dischargeKw().multiply(hours, MC)
                                .divide(battery.dischargeEfficiency(), MC), MC);
                BigDecimal expectedEnd = endEnergy.multiply(BigDecimal.valueOf(100), MC)
                        .divide(battery.capacityKwh(), MC);
                if (target.socEndPct().subtract(expectedEnd).abs().compareTo(EPSILON) > 0
                        || target.socEndPct().compareTo(battery.minSocPct().subtract(EPSILON)) < 0
                        || target.socEndPct().compareTo(battery.maxSocPct().add(EPSILON)) > 0) {
                    failures.add("BATTERY_SOC_CONSTRAINT_VIOLATED");
                }
                expectedSoc.put(battery.deviceId(), target.socEndPct());
                totalCharge = totalCharge.add(target.chargeKw(), MC);
                totalDischarge = totalDischarge.add(target.dischargeKw(), MC);
            }
            BigDecimal expectedGrid = slot.loadKw().subtract(slot.pvKw(), MC)
                    .add(totalCharge, MC).subtract(totalDischarge, MC);
            if (interval.plannedGridKw().subtract(expectedGrid).abs().compareTo(EPSILON) > 0
                    || interval.plannedGridKw().abs().compareTo(input.gridLimitKw().add(EPSILON)) > 0) {
                failures.add("GRID_CONNECTION_LIMIT_VIOLATED");
            }
        }
        return failures.stream().distinct().toList();
    }

    private record InstantDevice(java.time.Instant start, java.util.UUID deviceId) {}
}
