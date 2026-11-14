package dimblend.dragonchase.client;

import dimblend.dragonchase.entity.CrystalBeams;
import java.util.HashMap;
import java.util.Map;

/**
 * Client cache of the crystal entity ids that heal each dragon (see {@link CrystalBeams}).
 * Written on the client thread by the payload handler, read by {@link ChaseDragonRenderer}.
 */
public final class ClientCrystalBeams {
    private static final int[] NONE = new int[0];
    private static final Map<Integer, int[]> BY_DRAGON = new HashMap<>();

    public static void accept(CrystalBeams payload) {
        if (payload.crystalIds().length == 0) {
            BY_DRAGON.remove(payload.dragonId());
        } else {
            BY_DRAGON.put(payload.dragonId(), payload.crystalIds().clone());
        }
    }

    public static int[] idsFor(int dragonId) {
        int[] ids = BY_DRAGON.get(dragonId);
        return ids == null ? NONE : ids;
    }

    /** Dropped on logout so a new world never reuses stale entity ids. */
    public static void reset() {
        BY_DRAGON.clear();
    }

    private ClientCrystalBeams() {
    }
}
