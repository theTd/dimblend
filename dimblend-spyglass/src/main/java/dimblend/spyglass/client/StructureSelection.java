package dimblend.spyglass.client;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The one selected structure mark, if any.
 *
 * <p>The selection is keyed by {@link StructureKey} (structure id + start chunk), not by the glyph
 * or by the snapshot entry: a structure that was predicted ({@code ?}) and later shows up as a
 * saved start ({@code !}) keeps its key, so the selection survives that transition. Selecting the
 * same key again clears it; selecting another key replaces it.
 *
 * <p>An entry stores the union bounds' <b>top-centre anchor</b> and its horizontal centre at
 * selection time. That saved world position is what the marker is projected from and what the
 * {@code [128, 1024]} ring is measured against, so a momentarily empty snapshot, a pan, a FOV
 * change or a mark hidden by the overlap rule can never be mistaken for "the structure is gone".
 * The server-provided union bounds are stable, so the anchor does not drift.
 *
 * <p>{@link #pruneOutOfRange} is the only place that clears the selection by distance, and callers
 * must only run it while the player is scoping with a usable terrain fog: lowering the spyglass
 * keeps the selection, and an effect/fluid fog must not clear it either.
 *
 * <p>Client main thread only.
 */
final class StructureSelection {
    /**
     * One selected structure: its key, its horizontal union-bounds centre (the distance basis used
     * by {@link SpyglassDistance}) and the anchor's Y ({@code bounds.maxY() + 1}).
     */
    record Entry(StructureKey key, double centerX, double anchorY, double centerZ, boolean predicted) {
        Entry {
            Objects.requireNonNull(key, "key");
            requireFinite(centerX, "centerX");
            requireFinite(anchorY, "anchorY");
            requireFinite(centerZ, "centerZ");
        }

        /** The entry of an outline as sent by the server. */
        static Entry of(StructureOutline outline) {
            OutlineBox bounds = outline.bounds();
            return new Entry(
                    outline.key(),
                    bounds.centerX(),
                    bounds.maxY() + 1.0,
                    bounds.centerZ(),
                    outline.predicted());
        }

        /** Horizontal distance from a live position to the saved union-bounds centre. */
        double distanceTo(double playerX, double playerZ) {
            return Math.hypot(playerX - centerX, playerZ - centerZ);
        }
    }

    private static @Nullable Entry selected;

    /** The current selection, or {@code null}. */
    static @Nullable Entry current() {
        return selected;
    }

    static boolean isSelected(StructureKey key) {
        return selected != null && selected.key().equals(key);
    }

    /** Click behaviour: the same key cancels, a different key replaces. */
    static void toggle(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        selected = isSelected(entry.key()) ? null : entry;
    }

    /**
     * Refreshes the saved entry from a live mark of the same key, e.g. when the outline switches
     * from predicted to saved or the server sends finer bounds. Other keys are ignored.
     */
    static void observe(StructureKey key, boolean predicted, double centerX, double anchorY, double centerZ) {
        if (selected == null || !selected.key().equals(key)) {
            return;
        }
        if (selected.predicted() == predicted
                && selected.centerX() == centerX
                && selected.anchorY() == anchorY
                && selected.centerZ() == centerZ) {
            return;
        }
        selected = new Entry(key, centerX, anchorY, centerZ, predicted);
    }

    /**
     * Drops the selection when its saved centre really left the display ring.
     *
     * <p>Call only while scoping, and only with a usable terrain fog ({@code revealEnabled}): a fog
     * that is disabled by water, lava, powder snow, blindness or darkness says nothing about the
     * structure's distance.
     */
    static void pruneOutOfRange(double playerX, double playerZ, boolean revealEnabled) {
        if (!revealEnabled || selected == null) {
            return;
        }
        if (!SpyglassDistance.inRange(selected.distanceTo(playerX, playerZ))) {
            selected = null;
        }
    }

    /** Forgets the selection; called on dimension change, logout and the level-cache reset. */
    static void clear() {
        selected = null;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite " + name + ": " + value);
        }
    }

    private StructureSelection() {
    }
}
