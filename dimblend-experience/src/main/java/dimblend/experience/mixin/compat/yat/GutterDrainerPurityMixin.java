package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
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

/**
 * 缺口 4（世界采集那一半）：集水口自带的 {@code GutterFluidDrainingBehaviour} 并不继承 Create 的
 * {@code FluidDrainingBehaviour}（javap 核实：{@code extends BlockEntityBehaviour} 且自己声明
 * {@code getDrainableFluid(BlockPos)}），所以 {@code FluidDrainingBehaviourPurityMixin} 覆盖不到它。
 * 这里单独再包一层，按被抽方块位置补纯度。
 *
 * <p>{@code blockEntity} 声明在父类 {@code BlockEntityBehaviour} 上而非目标类自身，
 * 不能用 {@code @Shadow}（Mixin 0.8.5 apply 期会找不到字段直接崩溃），改为强转父类直读。</p>
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.block.GutterOutlet.GutterFluidDrainingBehaviour", remap = false)
public abstract class GutterDrainerPurityMixin {

    @WrapMethod(method = "getDrainableFluid", remap = false, require = 1, allow = 1)
    private FluidStack dimblend$stampGutterDrainedWater(BlockPos pos, Operation<FluidStack> original) {
        FluidStack result = original.call(pos);
        if (!DirtyWaterCompat.purityAvailable()
                || !WaterSourcePurityRules.isWater(result.isEmpty(), result.is(Fluids.WATER))) {
            return result;
        }
        SmartBlockEntity be = ((BlockEntityBehaviour) (Object) this).blockEntity;
        Level level = be == null ? null : be.getLevel();
        if (level == null || level.isClientSide) {
            return result;
        }
        DirtyWaterPurity.stampFluidPurity(result, WaterPurity.getBlockPurity(level, pos));
        return result;
    }
}
