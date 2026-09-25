package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.util.SortedArraySet;
import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the raw per-chunk ticket table and release queue for the stall monitor. */
@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {

    @Accessor("tickets")
    Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> dimblend$getTickets();

    /** Chunk keys whose tickets were removed and are pending the throttled release. */
    @Accessor("ticketsToRelease")
    LongSet dimblend$getTicketsToRelease();
}
