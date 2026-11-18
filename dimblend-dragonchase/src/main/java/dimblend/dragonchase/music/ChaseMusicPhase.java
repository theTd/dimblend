package dimblend.dragonchase.music;

/**
 * The chase BGM phase decided by the server and mirrored on every client.
 *
 * <p>This is deliberately separate from {@code DragonChaseSavedData.Phase}: the chase is
 * {@code WON} from the kill until the nest is gone, while the music has already started its
 * 16-second fade-out ({@link #IDLE} plus a client-side fade of the still-riding instance). Only
 * these three values ever travel over the wire, so both ends agree on the track table the index
 * points into ({@link DragonChaseSounds}).
 *
 * <p>No Minecraft or client types: this class is loaded on both sides.
 */
public enum ChaseMusicPhase {
    /** No chase BGM; receiving it while a track plays starts the 16-second fade-out. */
    IDLE(false),
    /** The single looping track chosen at {@code startChase}. */
    PHASE_A(true),
    /** The track chosen at the first drop below 50% HP; crossfaded in over 4 s. */
    PHASE_B(true);

    private final boolean audible;

    ChaseMusicPhase(boolean audible) {
        this.audible = audible;
    }

    /** True while this phase is a playing track (everything but {@link #IDLE}). */
    public boolean isAudible() {
        return this.audible;
    }

    /** Ordinal-safe lookup for a persisted or decoded byte; unknown values fall back to {@link #IDLE}. */
    public static ChaseMusicPhase byOrdinal(int ordinal) {
        ChaseMusicPhase[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
    }
}
