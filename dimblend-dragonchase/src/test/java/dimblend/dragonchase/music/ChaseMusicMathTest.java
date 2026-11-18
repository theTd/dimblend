package dimblend.dragonchase.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Phase B threshold and the two fade curves, without a client.
 *
 * <p>The threshold is the only place the "first drop strictly below 50% HP" rule lives, and the
 * curves are the only place the 4 s crossfade and the 16 s end fade live; both are exercised here
 * rather than in a live game, where a pause, a dropped payload or a missing track would hide a
 * regression.
 */
class ChaseMusicMathTest {
    @Test
    void phaseBStartsStrictlyBelowHalfHealth() {
        float maxHealth = 400.0F;
        // Exactly half is the boundary and must not trigger.
        assertFalse(ChaseMusicMath.phaseBReached(200.0F, maxHealth));
        // One tick of damage below the boundary does.
        assertTrue(ChaseMusicMath.phaseBReached(199.9F, maxHealth));
        // Any health at or below zero is a kill, not a phase change: the chase is ending.
        assertFalse(ChaseMusicMath.phaseBReached(0.0F, maxHealth));
        assertFalse(ChaseMusicMath.phaseBReached(-1.0F, maxHealth));
        // A degenerate max (unbound attribute) must not divide the world into "always Phase B".
        assertFalse(ChaseMusicMath.phaseBReached(10.0F, 0.0F));
    }

    @Test
    void phaseBFiresOnAScaledHealthPool() {
        // Two players: (400 + 200) = 600 max health, so the drop is at 300.
        assertFalse(ChaseMusicMath.phaseBReached(300.0F, 600.0F));
        assertTrue(ChaseMusicMath.phaseBReached(299.0F, 600.0F));
    }

    @Test
    void phaseBTriggersOnceAndNeverAgainAfterCrystalHealing() {
        // First drop: the chase is active, the latch is clear, the dragon is below half.
        assertTrue(ChaseMusicMath.shouldTriggerPhaseB(false, true, 199.0F, 400.0F));
        // Crystal healing pushes it back above half, then damage drops it again: the latch holds.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(true, true, 350.0F, 400.0F));
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(true, true, 199.0F, 400.0F));
        // No chase, no music transition, even below half.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(false, false, 199.0F, 400.0F));
        // The killing blow is not "below 50%": health is zero and the chase is ending.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(false, true, 0.0F, 400.0F));
        // Exactly half is not below half.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(false, true, 200.0F, 400.0F));
    }

    @Test
    void fadeOutReachesZeroAtTheSixteenSecondMark() {
        assertEquals(320, ChaseMusicMath.END_FADE_TICKS);
        assertEquals(1.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 0, ChaseMusicMath.END_FADE_TICKS));
        assertEquals(0.5F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 160, ChaseMusicMath.END_FADE_TICKS));
        assertEquals(0.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, ChaseMusicMath.END_FADE_TICKS,
                ChaseMusicMath.END_FADE_TICKS));
        // A fade that overshoots its length stays at the target instead of going negative.
        assertEquals(0.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 900, ChaseMusicMath.END_FADE_TICKS));
    }

    @Test
    void crossfadeRunsOverFourSecondsFromTheCurrentVolume() {
        assertEquals(80, ChaseMusicMath.CROSSFADE_TICKS);
        // Starting mid-fade (a kill during the crossfade) retargets from the current volume.
        assertEquals(0.5F, ChaseMusicMath.fadeGain(0.5F, 0.0F, 0, ChaseMusicMath.END_FADE_TICKS));
        assertEquals(0.25F, ChaseMusicMath.fadeGain(0.5F, 0.0F, 160, ChaseMusicMath.END_FADE_TICKS));
        // Fading in: the first tick is above zero so the engine creates the channel immediately.
        assertTrue(ChaseMusicMath.SILENT_START_VOLUME > 0.0F);
        assertEquals(ChaseMusicMath.SILENT_START_VOLUME,
                ChaseMusicMath.fadeGain(ChaseMusicMath.SILENT_START_VOLUME, 1.0F, 0, ChaseMusicMath.CROSSFADE_TICKS));
        assertEquals(1.0F, ChaseMusicMath.fadeGain(ChaseMusicMath.SILENT_START_VOLUME, 1.0F,
                ChaseMusicMath.CROSSFADE_TICKS, ChaseMusicMath.CROSSFADE_TICKS));
    }

    @Test
    void fadeGainClampsToTheEngineRange() {
        assertEquals(1.0F, ChaseMusicMath.fadeGain(2.0F, 3.0F, 10, 80));
        assertEquals(0.0F, ChaseMusicMath.fadeGain(-1.0F, -0.5F, 10, 80));
        // A zero-length fade applies its target on the spot.
        assertEquals(0.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 0, 0));
    }

    @Test
    void trackIndexIsClampedIntoTheFourOnDiskTracks() {
        assertEquals(4, ChaseMusicMath.TRACK_COUNT);
        assertEquals(0, ChaseMusicMath.clampTrack(-3));
        assertEquals(0, ChaseMusicMath.clampTrack(0));
        assertEquals(3, ChaseMusicMath.clampTrack(3));
        assertEquals(3, ChaseMusicMath.clampTrack(4));
        assertEquals(3, ChaseMusicMath.clampTrack(400));
    }

    @Test
    void phaseEnumIsIdleByDefaultAndOutOfRangeSafe() {
        assertEquals(ChaseMusicPhase.IDLE, ChaseMusicPhase.byOrdinal(0));
        assertEquals(ChaseMusicPhase.PHASE_A, ChaseMusicPhase.byOrdinal(1));
        assertEquals(ChaseMusicPhase.PHASE_B, ChaseMusicPhase.byOrdinal(2));
        assertEquals(ChaseMusicPhase.IDLE, ChaseMusicPhase.byOrdinal(-1));
        assertEquals(ChaseMusicPhase.IDLE, ChaseMusicPhase.byOrdinal(99));
        assertFalse(ChaseMusicPhase.IDLE.isAudible());
        assertTrue(ChaseMusicPhase.PHASE_A.isAudible());
        assertTrue(ChaseMusicPhase.PHASE_B.isAudible());
    }
}
