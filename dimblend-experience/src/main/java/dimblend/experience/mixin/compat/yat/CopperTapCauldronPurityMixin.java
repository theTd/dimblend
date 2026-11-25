package dimblend.experience.mixin.compat.yat;

import com.adonis.fluid.block.CopperTap.CopperTapBlockEntity;
import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.CauldronPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 缺口 12（铜龙头往炼药锅灌水那一半）：{@code CopperTapBlockEntity#fillWaterCauldronLevel}
 * 成功后把源流体的纯度并入方块态，避免"龙头灌进脏水、锅却显示可接受"。
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.block.CopperTap.CopperTapBlockEntity", remap = false)
public abstract class CopperTapCauldronPurityMixin {

    @Inject(method = "fillWaterCauldronLevel", at = @At("RETURN"), remap = false, require = 1, allow = 3)
    private void dimblend$stampCauldronFromTap(IFluidHandler handler, BlockPos pos, BlockState state, int level,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || !DirtyWaterCompat.purityAvailable() || handler == null) {
            return;
        }
        CopperTapBlockEntity self = (CopperTapBlockEntity) (Object) this;
        Level world = self.getLevel();
        FluidStack source = handler.getFluidInTank(0);
        int purity = WaterSourcePurityRules.effectivePurity(FluidPurityHelper.hasPurity(source),
                FluidPurityHelper.getPurity(source));
        CauldronPurity.blend(world, pos, purity);
    }
}
