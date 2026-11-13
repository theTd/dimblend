package dimblend.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Direct access to Sable force-load ticket tables for remote region purge. */
@Mixin(ServerSubLevelContainer.class)
public interface ServerSubLevelContainerAccessor {

    @Accessor("activeTickets")
    Object2ObjectMap<ServerSubLevel, ObjectSet<SubLevelLoadingTicket<?>>> dimblend$getActiveTickets();

    @Accessor("allTickets")
    Object2ObjectMap<UUID, SubLevelTicketInfo> dimblend$getAllTickets();
}
