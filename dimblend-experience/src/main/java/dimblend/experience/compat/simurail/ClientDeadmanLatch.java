package dimblend.experience.compat.simurail;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.Map;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧无人值守刹车锁存：由 {@code DeadmanLatchPayload} S2C 同步维护，
 * 供锁存粒子 mixin 读取。仅物理客户端读写；断线时复位，进服/换维度由补发重建。
 */
public final class ClientDeadmanLatch {

    private static final Map<ResourceKey<Level>, Set<UUID>> LATCHED_CARRIAGES = new ConcurrentHashMap<>();

    public static boolean isLatched(ResourceKey<Level> dim, UUID carriage) {
        return LATCHED_CARRIAGES.getOrDefault(dim, Set.of()).contains(carriage);
    }

    public static void set(ResourceKey<Level> dim, Collection<UUID> carriages) {
        if (!carriages.isEmpty()) {
            LATCHED_CARRIAGES.put(dim, Set.copyOf(carriages));
        } else {
            LATCHED_CARRIAGES.remove(dim);
        }
    }

    public static void reset() {
        LATCHED_CARRIAGES.clear();
    }

    private ClientDeadmanLatch() {
    }
}
