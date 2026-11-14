package dimblend.api;

import org.jetbrains.annotations.Nullable;

/**
 * Lets another mod take over the region progress bar's train marker (the "white line").
 *
 * <p>While an override is set, the server reports {@link Marker#centerX()} as the marker position
 * to every player in the rotating dimension instead of the locator's own pick, and the client
 * draws it red when {@link Marker#alert()} is true. Clearing the override restores the normal
 * locator on the next sync.
 *
 * <p>Server-thread only. Requirement: dimblend-dragonchase/docs/requirements.md §3.3.
 */
public final class BandTrainMarker {
    /** The marker to show: a world block X and whether to draw it as an alert (red). */
    public record Marker(double centerX, boolean alert) {
    }

    private static volatile Marker override;

    public static void setOverride(@Nullable Marker marker) {
        override = marker;
    }

    /**
     * Drops the override. Called when the server stops: the field is static, so a marker left by
     * a chase must not survive into the next world (where the red line would point at a nest
     * that no longer exists).
     */
    public static void clear() {
        override = null;
    }

    @Nullable
    public static Marker override() {
        return override;
    }

    private BandTrainMarker() {
    }
}
