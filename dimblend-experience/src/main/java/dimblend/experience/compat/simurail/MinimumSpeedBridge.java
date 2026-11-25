package dimblend.experience.compat.simurail;

import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * 对 {@code dimblend.api.MinimumSpeedState}（核心模组提供，Slice 1）的桥。
 *
 * <p>本模块可以单独安装（核心缺席时转向架/车钩仍要能跑），所以所有对核心类的引用都
 * 收在 {@link Holder} 里，只有 {@code dimblend} 在场时才会初始化它。
 * 核心缺席时：不豁免任何载具、也没有"低于最低速"的载具集合，
 * 也就是所有新行为自然退化为"不触发"。</p>
 */
public final class MinimumSpeedBridge {

    private static final boolean AVAILABLE = ModList.get().isLoaded("dimblend");

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** 该载具是否被玩家主动豁免"低于最低速刹车保持"（例如车站调度/停放）。 */
    public static boolean isExemptFromBrakeHold(ServerLevel level, UUID subLevelId) {
        return AVAILABLE && subLevelId != null && Holder.isExempt(level, subLevelId);
    }

    /** 当前正在牵引、但速度低于最低速阈值的载具集合（服务端权威）。 */
    public static Set<UUID> activePushingSubLevels(ServerLevel level) {
        return AVAILABLE ? Holder.activePushing(level) : Set.of();
    }

    private static final class Holder {
        private static boolean isExempt(ServerLevel level, UUID subLevelId) {
            return dimblend.api.MinimumSpeedState.isExemptFromBrakeHold(level, subLevelId);
        }

        private static Set<UUID> activePushing(ServerLevel level) {
            return dimblend.api.MinimumSpeedState.activePushingSubLevels(level);
        }
    }

    private MinimumSpeedBridge() {
    }
}
