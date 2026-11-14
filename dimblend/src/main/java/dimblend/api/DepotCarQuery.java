package dimblend.api;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Answers whether a force-loaded Sable sub-level is an idle station-depot spare car: a car the
 * starting depot assembled and kept force-loaded, which is not (yet) coupled into any consist.
 *
 * <p>Implemented by dimblend-experience (station depot assembly) and registered at mod
 * construction. Without an implementation every answer is false.
 *
 * <p>Server-thread only. Requirement: dimblend-dragonchase/docs/requirements.md §6 item 1 (assumption Q1).
 */
public interface DepotCarQuery {
    boolean isIdleDepotCar(ServerLevel level, UUID subLevel);

    DepotCarQuery NONE = (level, subLevel) -> false;

    static DepotCarQuery get() {
        return Holder.query;
    }

    static void register(DepotCarQuery query) {
        Holder.query = query == null ? NONE : query;
    }

    final class Holder {
        private static volatile DepotCarQuery query = NONE;

        private Holder() {
        }
    }
}
