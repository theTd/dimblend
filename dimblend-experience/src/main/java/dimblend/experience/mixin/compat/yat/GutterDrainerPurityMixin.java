package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 缺口 4（世界采集那一半）：集水口自带的 {@code GutterFluidDrainingBehaviour} 并不继承 Create 的
 * {@code FluidDrainingBehaviour}（javap 核实：{@code extends BlockEntityBehaviour} 且自己声明
 * {@code getDrainableFluid(BlockPos)}），所以 {@code FluidDrainingBehaviourPurityMixin} 覆盖不到它。
 * 这里单独再包一层，按被抽方块位置补纯度。
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.block.GutterOutlet.GutterFluidDrainingBehaviour", remap = false)
public abstract class GutterDrainerPurityMixin {

    @Shadow(remap = false)
    public SmartBlockEntity blockEntity;

    @WrapMethod(method = "getDrainableFluid", remap = false, require = 1, allow = 1)
    private FluidStack dimblend$stampGutterDrainedWater(BlockPos pos, Operation<FluidStack> original) {
        FluidStack result = original.call(pos);
        if (!DirtyWaterCompat.purityAvailable()
                || !WaterSourcePurityRules.isWater(result.isEmpty(), result.is(Fluids.WATER))) {
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
