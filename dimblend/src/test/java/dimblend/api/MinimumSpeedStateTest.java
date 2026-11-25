package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The cross-module handshake behind "brake holds at 0 m/s unless a minimum-speed mechanism wants
 * otherwise". Exercises the tick-injected overloads: every decision is a function of the
 * dimension, the sub-level id and {@code gameTime}, so no running level is needed.
 */
class MinimumSpeedStateTest {
    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> ROTATING = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"));

    private final UUID car = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @AfterEach
    void reset() {
        MinimumSpeedState.clearAll();
    }

    @Test
    void nothingIsGovernedUntilAMechanismClaimsIt() {
        assertFalse(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 100L));
        assertFalse(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 100L));
        assertEquals(Set.of(), MinimumSpeedState.activePushingSubLevels(OVERWORLD, 100L));
    }

    @Test
    void aGovernedSubLevelIsExemptFromTheBrakeHoldForTwoTicks() {
        MinimumSpeedState.markGoverned(OVERWORLD, car, 100L);
        assertTrue(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 100L));
        assertTrue(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 101L));
        assertTrue(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 102L));
        assertFalse(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 103L));
    }

    @Test
    void governingDoesNotClaimToBeAPush() {
        MinimumSpeedState.markGoverned(OVERWORLD, car, 100L);
        assertFalse(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 100L));
        assertEquals(Set.of(), MinimumSpeedState.activePushingSubLevels(OVERWORLD, 100L));
    }

    @Test
    void aPushAlsoGovernsAndHoldsForTheSmoothingWindow() {
        MinimumSpeedState.markActivelyPushing(OVERWORLD, car, 100L);
        assertTrue(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 100L));
        assertTrue(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 100L));
        // ACTIVE_PUSH_HOLD_TICKS = 5: still true through gameTime 105, false at 106.
        assertTrue(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 100L + MinimumSpeedState.ACTIVE_PUSH_HOLD_TICKS));
        assertFalse(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 101L + MinimumSpeedState.ACTIVE_PUSH_HOLD_TICKS));
    }

    @Test
    void aFreshPushExtendsBothDeadlines() {
        MinimumSpeedState.markActivelyPushing(OVERWORLD, car, 100L);
        MinimumSpeedState.markActivelyPushing(OVERWORLD, car, 104L);
        assertTrue(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 109L));
        assertFalse(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 110L));
    }

    @Test
    void pushesArePerDimension() {
        MinimumSpeedState.markActivelyPushing(OVERWORLD, car, 100L);
        assertTrue(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 100L));
        assertFalse(MinimumSpeedState.isActivelyPushing(ROTATING, car, 100L));
    }

    @Test
    void theActiveSetListsOnlyPushingSubLevelsAndIsStable() {
        MinimumSpeedState.markGoverned(OVERWORLD, other, 100L);
        MinimumSpeedState.markActivelyPushing(OVERWORLD, car, 100L);
        Set<UUID> active = MinimumSpeedState.activePushingSubLevels(OVERWORLD, 100L);
        assertEquals(Set.of(car), active);
        MinimumSpeedState.markActivelyPushing(OVERWORLD, other, 100L);
        // The earlier snapshot is a copy, so a later tick cannot mutate what a caller is holding.
        assertEquals(Set.of(car), active);
        assertEquals(Set.of(car, other), MinimumSpeedState.activePushingSubLevels(OVERWORLD, 100L));
    }

    @Test
    void expiryIsRelativeToTheCallersTick() {
        MinimumSpeedState.markActivelyPushing(OVERWORLD, car, 500L);
        assertEquals(Set.of(), MinimumSpeedState.activePushingSubLevels(OVERWORLD, 506L));
        assertFalse(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 503L));
    }

    @Test
    void clearForgetsOneDimensionAndClearAllForgetsEverything() {
        MinimumSpeedState.markActivelyPushing(OVERWORLD, car, 100L);
        MinimumSpeedState.markActivelyPushing(ROTATING, other, 100L);
        MinimumSpeedState.clear(OVERWORLD);
        assertFalse(MinimumSpeedState.isActivelyPushing(OVERWORLD, car, 100L));
        assertTrue(MinimumSpeedState.isActivelyPushing(ROTATING, other, 100L));
        MinimumSpeedState.clearAll();
        assertFalse(MinimumSpeedState.isActivelyPushing(ROTATING, other, 100L));
    }

    @Test
    void nullArgumentsAreIgnoredRatherThanThrowing() {
        MinimumSpeedState.markGoverned(null, car, 100L);
        MinimumSpeedState.markActivelyPushing(OVERWORLD, null, 100L);
        assertFalse(MinimumSpeedState.isExemptFromBrakeHold(OVERWORLD, car, 100L));
        assertEquals(Set.of(), MinimumSpeedState.activePushingSubLevels(null, 100L));
    }
}
