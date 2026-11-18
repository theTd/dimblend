package dimblend.experience.compat.dirtywater;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 从世界取水的脏水判定（森罗物语水壶 {@code TeapotItem#use} 用）。
 *
 * <p>水壶从世界水源装水时没有 ItemStack 纯度可读，纯度只能从方块位置推：YAT 对开阔水面
 * 按高度带与流动状态给纯度（平原/洞穴常态 0 = 脏，高山 1，流动水 +1），所以这里读
 * {@code WaterPurity.getBlockPurity} 而不是一律拒收。判定放在 {@code pickupBlock} 之前——
 * 取水会真的把水源变成空气，事后再拒就把水弄没了。</p>
 */
public final class DirtyWaterSourceRules {

    /** 玩家视线内（方块交互距离）的取水目标是否是脏水水源。 */
    public static boolean isDirtyWaterInReach(Level level, Player player) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return false;
        }
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, true);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = blockHit.getBlockPos();
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.is(Fluids.WATER) || !fluid.isSource()) {
            return false;
        }
        return DirtyWaterRules.isDirtyPurity(DirtyWaterPurity.blockPurity(level, pos));
    }

    private DirtyWaterSourceRules() {
    }
}
