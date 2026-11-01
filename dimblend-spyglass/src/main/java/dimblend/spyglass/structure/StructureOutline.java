package dimblend.spyglass.structure;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * One structure outline as transmitted to the client.
 *
 * <p>{@code key} identifies the individual start (structure id + start chunk) so a streamed
 * {@link StructureShape} can be linked to the outline it belongs to. Two outlines may share an id but
 * never a key.
 *
 * <p>{@code bounds} is the union box over <b>all</b> pieces and is computed before any piece cap is
 * applied, so the horizontal center used for distance never drifts when the transmitted piece list is
 * truncated. It must enclose every transmitted piece and the client must use it as-is instead of
 * recomputing a union per frame.
 *
 * <p>{@code predicted} says this start was <b>not read from the world</b>: it comes from replaying
 * world generation for a chunk that has no saved data ({@code StructurePredictor}). The geometry is
 * the real structure's piece boxes, but no blocks exist there yet, so the client marks the outline
 * instead of drawing a block mesh, and its label says so. A predicted outline never replaces a read
 * one: the server predicts only for chunks that hold no saved start.
 */
public record StructureOutline(
        StructureKey key, OutlineBox bounds, List<OutlineBox> pieces, boolean predicted) {
    public StructureOutline {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bounds, "bounds");
        pieces = List.copyOf(pieces);
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("An outline needs at least one piece");
        }
        for (OutlineBox piece : pieces) {
            if (!bounds.encloses(piece)) {
                throw new IllegalArgumentException("Outline bounds do not enclose every piece");
            }
        }
    }

    /** An outline read from saved world data. */
    public StructureOutline(StructureKey key, OutlineBox bounds, List<OutlineBox> pieces) {
        this(key, bounds, pieces, false);
    }

    /** Structure type shown to the player; equal to {@code key().id()}. */
    public ResourceLocation id() {
        return this.key.id();
    }

    /**
     * Builds an outline read from saved world data, with union bounds computed over the pieces.
     *
     * <p>The {@code predicted} flag deliberately has no default on the canonical constructor: a
     * caller building an outline from scratch must say where it came from, because the client renders
     * the two kinds differently.
     */
    public static StructureOutline of(StructureKey key, List<OutlineBox> pieces) {
        return new StructureOutline(key, OutlineBox.enclosing(pieces), pieces, false);
    }

    /**
     * Builds an outline whose start the world has not generated yet, from replayed world generation.
     *
     * <p>Callers must have ruled out a saved start for this key first — see
     * {@code ReadOnlyRegionScanner#scanInto}. The client draws this as a marker, not as blocks.
     */
    public static StructureOutline predicted(StructureKey key, List<OutlineBox> pieces) {
        return new StructureOutline(key, OutlineBox.enclosing(pieces), pieces, true);
    }

    /** Copy with a subset of the pieces, keeping the exact union bounds and the origin of the outline. */
    public StructureOutline withPieces(List<OutlineBox> subset) {
        return new StructureOutline(this.key, this.bounds, subset, this.predicted);
    }
}