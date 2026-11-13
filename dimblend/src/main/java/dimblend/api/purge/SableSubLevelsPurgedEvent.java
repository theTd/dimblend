package dimblend.api.purge;

import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * Fired on the NeoForge game event bus after rotating-dimension region purge removes one or more
 * unloaded Sable sub-levels belonging to {@code r.<regionX>.<regionZ>}.
 *
 * <p>Uses only vanilla and JDK types so listeners in other modules stay decoupled from Sable
 * internals when Sable is not installed.
 */
public final class SableSubLevelsPurgedEvent extends Event {
    private final ServerLevel level;
    private final int regionX;
    private final int regionZ;
    private final Set<UUID> subLevelIds;

    public SableSubLevelsPurgedEvent(ServerLevel level, int regionX, int regionZ, Set<UUID> subLevelIds) {
        this.level = level;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.subLevelIds = Set.copyOf(subLevelIds);
    }

    public ServerLevel level() {
        return this.level;
    }

    public int regionX() {
        return this.regionX;
    }

    public int regionZ() {
        return this.regionZ;
    }

    public Set<UUID> subLevelIds() {
        return this.subLevelIds;
    }
}
