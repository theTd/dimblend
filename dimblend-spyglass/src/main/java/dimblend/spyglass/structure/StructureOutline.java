package dimblend.spyglass.structure;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * One saved structure outline as transmitted to the client.
 *
 * <p>{@code key} identifies the individual saved start (structure id + start chunk) so that a
 * streamed {@link StructureShape} can be linked to the outline it belongs to. Two outlines may share
 * an id but never a key.
 *
 * <p>{@code bounds} is the union box over <b>all</b> saved pieces and is computed on the server
 * before any piece cap is applied, so the horizontal center used for distance never drifts when the
 * transmitted piece list is truncated. It must enclose every transmitted piece and the client must
 * use it as-is instead of recomputing a union per frame.
 */
public record StructureOutline(StructureKey key, OutlineBox bounds, List<OutlineBox> pieces) {
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

    /** Structure type shown to the player; equal to {@code key().id()}. */
    public ResourceLocation id() {
        return this.key.id();
    }

    /** Builds an outline whose union bounds are computed over the given pieces. */
    public static StructureOutline of(StructureKey key, List<OutlineBox> pieces) {
        return new StructureOutline(key, OutlineBox.enclosing(pieces), pieces);
    }

    /** Copy with a subset of the pieces, keeping the exact union bounds of the full outline. */
    public StructureOutline withPieces(List<OutlineBox> subset) {
        return new StructureOutline(this.key, this.bounds, subset);
    }
}
