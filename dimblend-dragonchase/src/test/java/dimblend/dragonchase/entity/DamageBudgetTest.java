package dimblend.dragonchase.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** §3.4 decision damage-pacing: at most {@code cap} health per rolling 20 ticks. */
class DamageBudgetTest {
    private static final float CAP = 8.0F;

    @Test
    void acceptsUpToTheWindowCap() {
        DamageBudget budget = new DamageBudget();
        assertEquals(5.0F, budget.accept(0, 5.0F, CAP), 1.0E-6F);
        assertEquals(3.0F, budget.accept(0, 5.0F, CAP), 1.0E-6F);
        assertEquals(0.0F, budget.accept(0, 1.0F, CAP), 1.0E-6F);
    }

    @Test
    void windowIsInclusiveOfTheCurrentTickAndExpiresExactlyTwentyTicksLater() {
        DamageBudget budget = new DamageBudget();
        assertEquals(CAP, budget.accept(0, CAP, CAP), 1.0E-6F);
        // Ticks 0..19 are inside the rolling window.
        assertEquals(0.0F, budget.accept(19, 5.0F, CAP), 1.0E-6F);
        // Tick 20: the hit at tick 0 is 20 ticks old and no longer counts.
        assertEquals(5.0F, budget.accept(20, 5.0F, CAP), 1.0E-6F);
        assertEquals(3.0F, budget.accept(20, 5.0F, CAP), 1.0E-6F);
    }

    @Test
    void partialAmountsAccumulateAcrossTheWindow() {
        DamageBudget budget = new DamageBudget();
        float total = 0.0F;
        for (int tick = 0; tick < 20; tick++) {
            total += budget.accept(tick, 1.0F, CAP);
        }
        assertEquals(CAP, total, 1.0E-6F);
        // Every tick 0..19 consumed 1: tick 20 frees exactly the tick-0 unit.
        assertEquals(1.0F, budget.accept(20, 1.0F, CAP), 1.0E-6F);
    }

    @Test
    void aTickJumpClearsStaleHistory() {
        DamageBudget budget = new DamageBudget();
        assertEquals(CAP, budget.accept(0, CAP, CAP), 1.0E-6F);
        assertEquals(CAP, budget.accept(100, CAP, CAP), 1.0E-6F);
    }

    @Test
    void aBackwardsTickResetsTheBudget() {
        DamageBudget budget = new DamageBudget();
        assertEquals(4.0F, budget.accept(50, 4.0F, CAP), 1.0E-6F);
        assertEquals(CAP, budget.accept(10, CAP, CAP), 1.0E-6F);
    }

    @Test
    void ignoresNonPositiveRequestsAndZeroCap() {
        DamageBudget budget = new DamageBudget();
        assertEquals(0.0F, budget.accept(0, 0.0F, CAP), 1.0E-6F);
        assertEquals(0.0F, budget.accept(0, -3.0F, CAP), 1.0E-6F);
        assertEquals(0.0F, budget.accept(0, 5.0F, 0.0F), 1.0E-6F);
    }

    @Test
    void resetFreesTheWholeWindow() {
        DamageBudget budget = new DamageBudget();
        assertEquals(CAP, budget.accept(0, CAP, CAP), 1.0E-6F);
        budget.reset();
        assertEquals(CAP, budget.accept(1, CAP, CAP), 1.0E-6F);
    }
}
