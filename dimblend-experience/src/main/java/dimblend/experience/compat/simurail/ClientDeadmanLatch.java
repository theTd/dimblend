package dimblend.experience.compat.simurail;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧无人值守刹车锁存：由 {@code DeadmanLatchPayload} S2C 同步维护，
 * 供锁存粒子 mixin 读取。仅物理客户端读写；断线时复位，进服/换维度由补发重建。
 */
public final class ClientDeadmanLatch {

    private static final Set<ResourceKey<Level>> LATCHED_DIMS = ConcurrentHashMap.newKeySet();

    public static boolean isLatched(ResourceKey<Level> dim) {
        return LATCHED_DIMS.contains(dim);
    }

    public static void set(ResourceKey<Level> dim, boolean latched) {
        if (latched) {
            LATCHED_DIMS.add(dim);
        } else {
            LATCHED_DIMS.remove(dim);
        }
    }

    public static void reset() {
        LATCHED_DIMS.clear();
    }

    private ClientDeadmanLatch() {
    }
}
