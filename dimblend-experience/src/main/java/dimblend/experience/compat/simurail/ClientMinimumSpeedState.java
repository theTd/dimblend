package dimblend.experience.compat.simurail;

import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端侧的"低于最低速牵引"集合缓存（对应 {@link MinimumSpeedActivePayload}）。
 *
 * <p>只读、无状态副作用：粒子 mixin 每 tick 查一次；断开连接/切换存档由
 * {@code DimBlendClient} 的登出入口调 {@link #reset()} 清掉——否则上一个存档的
 * 载具 UUID 会留在缓存里，粒子会在不相干的载具上继续喷。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientMinimumSpeedState {

    private static final java.util.Map<ResourceLocation, Set<UUID>> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void accept(MinimumSpeedActivePayload payload) {
        ACTIVE.put(payload.dimension(), Set.copyOf(payload.subLevelIds()));
    }

    public static boolean isActive(Level level, UUID subLevelId) {
        if (level == null || subLevelId == null) {
            return false;
        }
        Set<UUID> ids = ACTIVE.get(level.dimension().location());
        return ids != null && ids.contains(subLevelId);
    }

    public static void reset() {
        ACTIVE.clear();
    }

    private ClientMinimumSpeedState() {
    }
}
