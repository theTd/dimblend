package dimblend.dragonchase.music;

/**
 * The chase BGM phase decided by the server and mirrored on every client.
 *
 * <p>This is deliberately separate from {@code DragonChaseSavedData.Phase}: the chase is
 * {@code WON} from the kill until the nest is gone, while the music plays a single one-shot
 * epilogue track ({@link #PHASE_END}) and then falls back to silence. Only these four values ever
 * travel over the wire, so both ends agree on the track table the index points into
 * ({@link DragonChaseSounds}).
 *
 * <p>No Minecraft or client types: this class is loaded on both sides.
 */
public enum ChaseMusicPhase {
    /** No chase BGM; receiving it while a track plays starts the 16-second fade-out. */
    IDLE(false, false),
    /** The single looping track chosen at {@code startChase}. */
    PHASE_A(true, true),
    /** The track chosen at the first drop below 50% HP; crossfaded in over 4 s. */
    PHASE_B(true, true),
    /**
     * The victory epilogue: the Phase A track of the same author (see
     * {@link ChaseMusicMath#phaseEndTrack}), played <b>once</b> after the ender dragon dies.
     *
     * <p>Never persisted as a saved-data phase — the server writes {@link #IDLE} first and only
     * broadcasts this phase once, so a reload, a reconnect or a sync can never replay it.
     */
    PHASE_END(true, false);

    private final boolean audible;
    private final boolean looping;

    ChaseMusicPhase(boolean audible, boolean looping) {
        this.audible = audible;
        this.looping = looping;
    }

    /** True while this phase is a playing track (everything but {@link #IDLE}). */
    public boolean isAudible() {
        return this.audible;
    }

    /**
     * True when the client must keep restarting the file at its end.
     *
     * <p>The fight tracks loop forever while the chase runs; {@link #PHASE_END} is a single
     * pass and must stop at the end of the file (its owning controller also drops the MUSIC block
     * the moment the stream finishes).
     */
    public boolean isLooping() {
        return this.looping;
    }

    /** Ordinal-safe lookup for a persisted or decoded byte; unknown values fall back to {@link #IDLE}. */
    public static ChaseMusicPhase byOrdinal(int ordinal) {
        ChaseMusicPhase[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
    }
}
