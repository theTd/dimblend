package dimblend.client.bandrift;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Live A/B switch for the band-boundary visual, driven by {@code /dimblendrift mode}.
 *
 * <ul>
 *   <li>{@link #RIFT} — default. The S2 volumetric double universe: Sodium chunk shaders are
 *       masked per fragment, real far-side chunks are drawn a second time inside the shell, and
 *       M1 mirage chunks fill both sides with the near band's terrain. Without Sodium (or when the
 *       shader injection missed) this degrades to {@link #MEMBRANE} automatically.</li>
 *   <li>{@link #MEMBRANE} — the Phase-1 artefact for comparison: one batched octagonal membrane
 *       per visible boundary column, no mirage, no second terrain pass.</li>
 *   <li>{@link #LEGACY} — the new system draws nothing at all; the original per-gate block-entity
 *       renderer (293 gateway cubes per column) is handed back, which is also the "everything off"
 *       state for isolating frame cost. {@code off} is accepted as an alias.</li>
 * </ul>
 *
 * <p>Read on every render dispatch, so switching takes effect on the next frame without a resource
 * reload. Mirage requests are only sent in {@link #RIFT} mode, so the other modes cost the server
 * nothing.</p>
 */
public enum BandRiftMode {
    RIFT,
    MEMBRANE,
    LEGACY;

    private static volatile BandRiftMode current = RIFT;

    public static BandRiftMode current() {
        return current;
    }

    public static void set(@Nullable BandRiftMode mode) {
        current = mode == null ? RIFT : mode;
    }

    /** Lower-case id used by the command and every log line. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Nullable
    public static BandRiftMode parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "rift" -> RIFT;
            case "membrane" -> MEMBRANE;
            case "legacy", "off" -> LEGACY;
            default -> null;
        };
    }
}
