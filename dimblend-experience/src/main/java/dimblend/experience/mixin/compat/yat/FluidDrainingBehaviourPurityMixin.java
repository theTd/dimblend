package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 缺口 3（前半）：Create 软管滑轮 / 出水口抽世界流体的统一入口
 * {@code FluidDrainingBehaviour#getDrainableFluid(BlockPos)}。
 *
 * <p>软管滑轮、软管出水口、以及所有用 Drainer 的机器都从这里取"这个世界方块的流体是什么"，
 * 所以在这一层补纯度覆盖面最大。无限水体（{@code canPullFluidsFrom == INFINITE}）走的是
 * 根方块坐标，同样带得出纯度；这里不做特殊分支，只按传入的 {@code pos} 读。</p>
 */
@Mixin(FluidDrainingBehaviour.class)
public abstract class FluidDrainingBehaviourPurityMixin {

    @Shadow(remap = false)
    public com.simibubi.create.foundation.blockEntity.SmartBlockEntity blockEntity;

    @WrapMethod(method = "getDrainableFluid", remap = false, require = 1, allow = 1)
    private FluidStack dimblend$stampDrainableWater(BlockPos pos, Operation<FluidStack> original) {
        FluidStack result = original.call(pos);
        if (!DirtyWaterCompat.purityAvailable() || !WaterSourcePurityRules.isWater(result.isEmpty(), result.is(Fluids.WATER))) {
            return result;
        }
        Level level = this.blockEntity.getLevel();
        if (level == null || level.isClientSide) {
            return result;
        }
        DirtyWaterPurity.stampFluidPurity(result, WaterPurity.getBlockPurity(level, pos));
        return result;
    }
}
