package dimblend.blocks.compat.enderstorage;

import org.jetbrains.annotations.Nullable;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * 列车末影物流箱的 Sable 结构放置判定：双端通用（{@code ClientLevel} 与 {@code ServerLevel}
 * 均实现 {@code SubLevelContainerHolder}，{@code SableCompanion.INSTANCE.getContaining} 双端一致）。
 *
 * <p>对 {@code sable-companion} 的类型引用隔离在内部类 {@link SableProbe} 中，
 * 仅在 {@code ModList.get().isLoaded("sable")} 为真时解析；Sable 未安装时一律返回 {@code false}
 *（严格遵守「仅能放置于 sable 上」）。</p>
 */
public final class SablePlacementCheck {

    public static boolean canPlace(Level level, BlockPos pos, @Nullable Entity placer) {
        if (!TrainEnderChests.STRICT_SABLE_IN_CREATIVE
                && placer instanceof Player player
                && player.isCreative()) {
            return true;
        }
        if (!ModList.get().isLoaded("sable")) {
            return false;
        }
        return SableProbe.isInsideSubLevel(level, pos);
    }

    private static final class SableProbe {
        private static boolean isInsideSubLevel(Level level, BlockPos pos) {
            return SableCompanion.INSTANCE.getContaining(level, (Vec3i) pos) != null;
        }
    }

    private SablePlacementCheck() {
    }
}
