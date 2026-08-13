package io.vpp.platformapi.schedule;

import static io.vpp.platformapi.schedule.OptimizationModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RuleBaselineOptimizerTest {
    private final RuleBaselineOptimizer optimizer = new RuleBaselineOptimizer();
    private final ScheduleConstraintValidator validator = new ScheduleConstraintValidator();

    @Test
    void goldenPlanIsDeterministicEconomicalAndConstraintSafe() {
        Instant start = Instant.parse("2026-08-14T00:00:00Z");
        List<Slot> slots = List.of(
                slot(start, "50", "0", "0.10"),
                slot(start.plus(15, ChronoUnit.MINUTES), "50", "0", "0.10"),
                slot(start.plus(30, ChronoUnit.MINUTES), "50", "0", "1.00"),
                slot(start.plus(45, ChronoUnit.MINUTES), "50", "0", "1.00"));
        Battery battery = battery("50");
        Input input = new Input(slots, List.of(battery), bd("100"), bd("0.02"));

        Result first = optimizer.optimize(input);
        Result second = optimizer.optimize(input);

        assertThat(first).isEqualTo(second);
        assertThat(first.feasible()).isTrue();
        assertThat(validator.validate(input, first)).isEmpty();
        assertThat(first.summary().savings()).isPositive();
        assertThat(first.targets()).anyMatch(target -> target.chargeKw().signum() > 0)
                .anyMatch(target -> target.dischargeKw().signum() > 0);
    }

    @Test
    void reportsInfeasibleInsteadOfRelaxingGridLimit() {
        Instant start = Instant.parse("2026-08-14T00:00:00Z");
        Input input = new Input(List.of(slot(start, "200", "0", "1")),
                List.of(battery("20")), bd("100"), BigDecimal.ZERO);

        Result result = optimizer.optimize(input);

        assertThat(result.feasible()).isFalse();
        assertThat(result.reasons()).containsExactly("GRID_IMPORT_LIMIT_UNSATISFIABLE");
        assertThat(result.intervals()).isEmpty();
    }

    @Test
    void randomizedFeasibleInputsPreserveAllHardConstraints() {
        for (int seed = 1; seed <= 100; seed++) {
            Instant start = Instant.parse("2026-08-14T00:00:00Z");
            List<Slot> slots = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                BigDecimal load = bd(Integer.toString(20 + Math.floorMod(seed * 17 + index * 11, 50)));
                BigDecimal pv = bd(Integer.toString(Math.floorMod(seed * 7 + index * 13, 20)));
                BigDecimal price = index < 8 ? bd("0.20") : bd("0.90");
                slots.add(slot(start.plus(index * 15L, ChronoUnit.MINUTES), load.toPlainString(),
                        pv.toPlainString(), price.toPlainString()));
            }
            Input input = new Input(slots, List.of(battery("50")), bd("120"), bd("0.03"));
            Result result = optimizer.optimize(input);
            assertThat(result.feasible()).as("seed %s", seed).isTrue();
            assertThat(validator.validate(input, result)).as("seed %s", seed).isEmpty();
        }
    }

    private Slot slot(Instant start, String load, String pv, String price) {
        return new Slot(start, start.plus(15, ChronoUnit.MINUTES), bd(load), bd(pv), bd(price));
    }

    private Battery battery(String initialSoc) {
        return new Battery(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1, bd("100"), bd("40"),
                bd("40"), bd("20"), bd("80"), bd("0.90"), bd("0.90"), bd(initialSoc));
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
