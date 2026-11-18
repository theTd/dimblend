package dimblend.dragonchase.music;

/**
 * The chase BGM's numbers, as pure functions: the 50% drop that starts Phase B, the track index
 * bounds and the two linear fade curves the client controller ticks.
 *
 * <p>No Minecraft types, so the unit test covers every rule without a client or a world. Fades are
 * counted in client ticks (20 per second), never wall-clock time: a paused game freezes tickable
 * sounds, and a fade measured in milliseconds would finish unnoticed in the background.
 */
public final class ChaseMusicMath {
    /** Phase A/Phase B each have four tracks on disk ({@code music.phase_a_1..4}). */
    public static final int TRACK_COUNT = 4;

    /**
     * §3.4: Phase B starts at the first drop strictly below 50% of the spawn health. Strictly:
     * exactly 50% does not trigger, and a hit that kills the dragon outright (health 0) must not
     * start Phase B either — the chase is over, not escalating.
     */
    public static final double PHASE_B_HEALTH_FRACTION = 0.5D;

    /** 4 s: the Phase A -> Phase B overlapping crossfade (brief: {@code 80} ticks). */
    public static final int CROSSFADE_TICKS = 80;

    /** 16 s: the fade-out after the chase ends (brief: {@code 320} ticks). */
    public static final int END_FADE_TICKS = 320;

    /**
     * The crossfaded-in track's first volume. Must stay above zero so the eager
     * {@code volume <= 0 && !canStartSilent()} skip is not reached (the instance also answers
     * {@code canStartSilent() == true}, but starting audible is the belt to that brace).
     */
    public static final float SILENT_START_VOLUME = 0.001F;

    /** True when the dragon's live health has dropped below the Phase B threshold. */
    public static boolean phaseBReached(float health, float maxHealth) {
        return health > 0.0F && maxHealth > 0.0F && health < maxHealth * (float) PHASE_B_HEALTH_FRACTION;
    }

    /**
     * The whole one-way Phase B rule: trigger only while the chase is active, only once per chase,
     * and only on a live dragon strictly below half health. The latch wins over the health, so
     * crystal healing back above 50% and dropping again never fires twice.
     */
    public static boolean shouldTriggerPhaseB(boolean alreadyTriggered, boolean chaseActive, float health,
            float maxHealth) {
        return chaseActive && !alreadyTriggered && phaseBReached(health, maxHealth);
    }

    /** A track index from the server clamped into the four on-disk tracks. */
    public static int clampTrack(int track) {
        return Math.max(0, Math.min(TRACK_COUNT - 1, track));
    }

    /**
     * A linear fade from {@code from} to {@code to}: {@code elapsedTicks == 0} is the start value,
     * {@code elapsedTicks >= totalTicks} the target. Clamped to [0, 1] so a sampled volume can never
     * leave the engine's valid range.
     */
    public static float fadeGain(float from, float to, int elapsedTicks, int totalTicks) {
        if (totalTicks <= 0) {
            return clampVolume(to);
        }
        float progress = Math.max(0.0F, Math.min(1.0F, (float) elapsedTicks / (float) totalTicks));
        return clampVolume(from + (to - from) * progress);
    }

    private static float clampVolume(float volume) {
        return Math.max(0.0F, Math.min(1.0F, volume));
    }

    private ChaseMusicMath() {
    }
}
