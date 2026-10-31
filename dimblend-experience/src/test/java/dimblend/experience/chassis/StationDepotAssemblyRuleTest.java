package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** R6 depot assembly: the step must be idempotent, retryable, and never fight a player. */
class StationDepotAssemblyRuleTest {
    private static final double NEAR = 1.0D;
    private static final int GIVE_UP_AFTER = StationDepotAssemblyRule.MAX_ATTEMPTS;
    private static final double FAR = (double) StationDepotAssemblyRule.TRIGGER_RADIUS + 1.0D;

    private static StationDepotAssemblyRule.Step step(
            boolean built, boolean assembled, int attempts, boolean present, boolean loaded, double distanceSq) {
        return StationDepotAssemblyRule.step(built, assembled, attempts, present, loaded, distanceSq);
    }

    @Test
    void aFreshSiteBuildsOnlyOnceAPlayerIsNear() {
        assertEquals(StationDepotAssemblyRule.Step.BUILD, step(false, false, 0, false, true, NEAR));
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(false, false, 0, false, true, FAR * FAR));
    }

    @Test
    void aBuiltSiteIsAssembledWithoutRebuildingIt() {
        assertEquals(StationDepotAssemblyRule.Step.ASSEMBLE, step(true, false, 0, true, true, NEAR));
    }

    @Test
    void assemblyWaitsForTheNextChanceRatherThanRebuilding() {
        // built + far: no build, no assembly, and crucially the blocks are not rewritten.
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(true, false, 0, true, true, FAR * FAR));
    }

    @Test
    void anAssembledSiteIsNeverTouchedAgain() {
        for (int attempts = 0; attempts <= GIVE_UP_AFTER + 2; attempts++) {
            assertEquals(StationDepotAssemblyRule.Step.IDLE, step(true, true, attempts, true, true, NEAR),
                    "idempotent: no second sub-level ever");
            assertEquals(StationDepotAssemblyRule.Step.IDLE, step(false, true, attempts, false, true, NEAR));
        }
    }

    @Test
    void unloadedChunksDeferAndNeverForceALoad() {
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(false, false, 0, false, false, NEAR));
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(true, false, 0, true, false, NEAR));
    }

    @Test
    void repeatedFailuresStopInsteadOfSpinning() {
        assertEquals(StationDepotAssemblyRule.Step.BUILD, step(false, false, GIVE_UP_AFTER - 1, false, true, NEAR));
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(false, false, GIVE_UP_AFTER, false, true, NEAR));
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(false, false, GIVE_UP_AFTER + 5, false, true, NEAR));
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(true, false, GIVE_UP_AFTER, true, true, NEAR));
    }

    @Test
    void aSiteWhoseBlocksWereRemovedIsDroppedInsteadOfRebuilt() {
        assertEquals(StationDepotAssemblyRule.Step.GIVE_UP, step(true, false, 0, false, true, NEAR),
                "a player cleared it: do not put it back");
    }

    @Test
    void theRadiusIsInclusive() {
        double edge = StationDepotAssemblyRule.TRIGGER_RADIUS;
        assertEquals(StationDepotAssemblyRule.Step.BUILD, step(false, false, 0, false, true, edge * edge));
        assertEquals(StationDepotAssemblyRule.Step.IDLE, step(false, false, 0, false, true, FAR * FAR));
    }
}