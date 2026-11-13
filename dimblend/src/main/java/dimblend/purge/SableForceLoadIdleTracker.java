package dimblend.purge;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks continuous idle eligibility before unmanned force-loaded Sable chains have their tickets
 * removed. A chain that becomes ineligible (for example, when a player enters its keep window)
 * resets its timer immediately.
 */
public final class SableForceLoadIdleTracker {
    private final Object2LongOpenHashMap<UUID> firstEligibleTick = new Object2LongOpenHashMap<>();

    public List<UUID> update(Collection<UUID> eligibleChainRoots, long nowTick, long idleTicks) {
        Set<UUID> eligibleSet = Set.copyOf(eligibleChainRoots);
        var iter = this.firstEligibleTick.object2LongEntrySet().iterator();
        while (iter.hasNext()) {
            Object2LongMap.Entry<UUID> entry = iter.next();
            if (!eligibleSet.contains(entry.getKey())) {
                iter.remove();
            }
        }
        List<UUID> ready = new ArrayList<>();
        for (UUID root : eligibleSet) {
            long first = this.firstEligibleTick.containsKey(root)
                    ? this.firstEligibleTick.getLong(root)
                    : nowTick;
            this.firstEligibleTick.putIfAbsent(root, first);
            if (nowTick - first >= idleTicks) {
                ready.add(root);
                this.firstEligibleTick.removeLong(root);
            }
        }
        return ready;
    }

    public int tracked() {
        return this.firstEligibleTick.size();
    }

    public void clear() {
        this.firstEligibleTick.clear();
    }
}
