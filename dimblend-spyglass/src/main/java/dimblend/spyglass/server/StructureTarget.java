package dimblend.spyglass.server;

import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * One whitelisted structure for a scan: the id shown to the client plus the registry holder whose
 * placements drive the candidate enumeration. Resolved on the server thread.
 */
record StructureTarget(ResourceLocation id, Holder<Structure> holder) {
    StructureTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(holder, "holder");
    }
}
