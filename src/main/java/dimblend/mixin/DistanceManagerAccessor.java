package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.util.SortedArraySet;
import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the raw per-chunk ticket table for the foreign-generation yield scan. */
@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {

    @Accessor("tickets")
    Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> dimblend$getTickets();
}
