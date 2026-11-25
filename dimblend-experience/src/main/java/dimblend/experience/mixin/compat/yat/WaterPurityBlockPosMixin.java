package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 第 1 个缺口：Sable 世界坐标 Y 带投影（所有 YAT 方块纯度判定的总闸）。
 *
 * <p>{@code WaterPurity#getBlockPurity} 先看 {@code pos.getY()} 落在哪个高度带再决定基准纯度
 * （海平面以下/以上两份逻辑），随后才看水源/炼药锅。Sable 子层级上的方块坐标是 plot 坐标
 * （Y 在 0 附近），直接喂进去会命中错误的"高度带"，同一桶水在船上和在陆地上纯度过不同。</p>
 *
 * <p>修法：进函数前把 {@code pos} 用 {@link SableWorldPosition#projectBlock} 投到世界坐标；
 * 世界坐标幂等（非 Sable 方块原样返回），所以陆地上行为零变化。</p>
 */
@Mixin(WaterPurity.class)
public abstract class WaterPurityBlockPosMixin {

    @WrapMethod(method = "getBlockPurity", remap = false, require = 1, allow = 1)
    private static int dimblend$projectSablePos(Level level, BlockPos pos, Operation<Integer> original) {
        return original.call(level, SableWorldPosition.projectBlock(level, pos));
    }
}
