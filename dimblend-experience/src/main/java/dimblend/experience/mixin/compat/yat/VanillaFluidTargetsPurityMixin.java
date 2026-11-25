package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 缺口 2：Create 开口管（{@code OpenEndedPipe#removeFluidFromSpace}）与
 * {@code VanillaFluidTargets#drainBlock}（炼药锅/含水方块）抽世界水时补纯度。
 *
 * <p>{@code removeFluidFromSpace} 内部<b>只</b>通过 {@code drainBlock} 拿世界流体（simulate
 * 与 execute 各一次），所以卡 {@code drainBlock} 就能一次覆盖两条路径。纯度必须在
 * <b>调用前</b>读：execute 会把方块一起拆掉，回头再读就是空气（0）。</p>
 *
 * <p>两处都只对水生效；非水（例如 Create 的巧克力）不动。</p>
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.pipes.VanillaFluidTargets", remap = false)
public abstract class VanillaFluidTargetsPurityMixin {

    @WrapMethod(method = "drainBlock", remap = false, require = 1, allow = 1)
    private static FluidStack dimblend$stampDrainedWorldWater(Level level, BlockPos pos, BlockState state,
            boolean simulate, Operation<FluidStack> original) {
        int purity = DirtyWaterCompat.purityAvailable() && !level.isClientSide
                ? WaterPurity.getBlockPurity(level, pos)
                : WaterSourcePurityRules.INFINITE_SOURCE_PURITY;
        FluidStack drained = original.call(level, pos, state, simulate);
        if (DirtyWaterCompat.purityAvailable()
                && WaterSourcePurityRules.isWater(drained.isEmpty(), drained.is(Fluids.WATER))) {
            DirtyWaterPurity.stampFluidPurity(drained, WaterSourcePurityRules.clampPurity(purity));
        }
        return drained;
    }
}
