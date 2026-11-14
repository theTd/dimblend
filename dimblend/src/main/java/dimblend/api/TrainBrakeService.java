package dimblend.api;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * The "ender brake" (末影刹车): forced brake + forced overload + purple particles + map-wide brake
 * broadcast, latched on a whole coupled consist until any raw redstone level change on it.
 *
 * <p>Implemented by dimblend-experience (its unattended-train emergency brake) and registered at
 * mod construction when Simurail is present. Callers must treat the absence of an implementation
 * as "feature unavailable" and must not fail.
 *
 * <p>Server-thread only. Requirement: dimblend-dragonchase/docs/requirements.md §3.4, §2.3.
 */
public interface TrainBrakeService {
    /**
     * Latches the ender brake on the consist containing sub-level {@code carriage}.
     *
     * @return true when the consist was found and is now latched
     */
    boolean latch(ServerLevel level, UUID carriage);

    /** True when the consist containing {@code carriage} is currently latched. */
    boolean isLatched(ServerLevel level, UUID carriage);

    TrainBrakeService NONE = new TrainBrakeService() {
        @Override
        public boolean latch(ServerLevel level, UUID carriage) {
            return false;
        }

        @Override
        public boolean isLatched(ServerLevel level, UUID carriage) {
            return false;
        }
    };

    static TrainBrakeService get() {
        return Holder.service;
    }

    static void register(TrainBrakeService service) {
        Holder.service = service == null ? NONE : service;
    }

    final class Holder {
        private static volatile TrainBrakeService service = NONE;

        private Holder() {
        }
    }
}
