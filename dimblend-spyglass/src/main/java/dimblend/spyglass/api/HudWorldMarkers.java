package dimblend.spyglass.api;

import dimblend.spyglass.DimBlendSpyglass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * World positions another mod wants drawn on the HUD with the spyglass's {@code 2x} glyph
 * projection.
 *
 * <p>The spyglass already projects its own structure marks through
 * {@code projection x modelView} and draws them at twice the font size. This registry lets a mod
 * share that projection without importing any client class: a provider returns plain coordinates
 * each frame, and the HUD draws the glyph at the projected point. External markers are <b>not</b>
 * structure marks: they never enter the structure selection, cannot be click-selected as one, and
 * are never pruned by the structure display ring ({@code [64, 1024]}).
 *
 * <p>Threading: {@link #register} may be called from mod setup, {@link #current} from the client
 * render thread. A provider that throws is skipped for that frame, and duplicate ids keep the first
 * marker so one broken integration cannot break the HUD.
 */
public final class HudWorldMarkers {
    /**
     * One glyph to project at a world position.
     *
     * @param id                       stable identity across frames, unique among all providers
     * @param glyph                    text drawn at twice the font size; usually one character
     * @param x                        world X of the anchor
     * @param y                        world Y of the anchor
     * @param z                        world Z of the anchor
     * @param guideWithArrowAndDistance also drive the edge arrow and the crosshair distance readout
     *                                 for this marker, scoping or not
     */
    public record Marker(String id, String glyph, double x, double y, double z, boolean guideWithArrowAndDistance) {
        public Marker {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("Blank marker id");
            }
            Objects.requireNonNull(glyph, "glyph");
            if (glyph.isEmpty()) {
                throw new IllegalArgumentException("Empty marker glyph for " + id);
            }
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
        }

        private static void requireFinite(double value, String name) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite " + name + ": " + value);
            }
        }
    }

    /** Supplies this frame's markers; called on the client render thread, never from a worker. */
    @FunctionalInterface
    public interface Provider {
        List<Marker> markers();
    }

    private static final CopyOnWriteArrayList<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Registers a provider once; registering the same instance again is a no-op. */
    public static void register(Provider provider) {
        PROVIDERS.addIfAbsent(Objects.requireNonNull(provider, "provider"));
    }

    /** Removes a provider, e.g. when its feature is turned off; absent providers are ignored. */
    public static void unregister(Provider provider) {
        PROVIDERS.remove(provider);
    }

    /**
     * The markers of the current frame, in provider order, with duplicate ids dropped.
     *
     * <p>Called by the spyglass HUD layer; other mods normally only implement {@link Provider}.
     */
    public static List<Marker> current() {
        List<Marker> markers = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Provider provider : PROVIDERS) {
            List<Marker> provided;
            try {
                provided = provider.markers();
            } catch (RuntimeException exception) {
                // A broken provider must not take the whole HUD down; it gets another try next frame.
                DimBlendSpyglass.LOGGER.debug("HUD marker provider failed: {}", exception.toString());
                continue;
            }
            if (provided == null) {
                continue;
            }
            for (Marker marker : provided) {
                if (marker != null && ids.add(marker.id())) {
                    markers.add(marker);
                }
            }
        }
        return List.copyOf(markers);
    }

    /** Drops every provider; only for tests, production registrations live for the client's lifetime. */
    static void clear() {
        PROVIDERS.clear();
    }

    private HudWorldMarkers() {
    }
}
