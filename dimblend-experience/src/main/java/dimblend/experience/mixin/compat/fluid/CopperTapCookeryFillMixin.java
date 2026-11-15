package dimblend.experience.mixin.compat.fluid;

import com.adonis.fluid.block.CopperTap.CopperTapBlockEntity;
import dimblend.experience.compat.fluid.CookeryCopperTapSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让铜龙头（{@code fluid:copper_tap}）能向正下方的森罗物语厨房汤锅与水壶注入 {@code 1000 mB} 水。
 */
@Mixin(value = CopperTapBlockEntity.class, remap = false)
public abstract class CopperTapCookeryFillMixin {

    @Shadow
    private FluidStack renderingFluid;

    @Shadow
    private void sendFillingParticles(BlockPos targetPos, FluidStack fluid) {
    }

    @Inject(
            method = "tryProcess(Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;"
                    + "Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1
    )
    private void dimblend$fillCookeryPotOrKettle(
            IFluidHandler source,
            BlockPos targetPos,
            Direction facing,
            BlockPos sourcePos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        CopperTapBlockEntity self = (CopperTapBlockEntity) (Object) this;
        Level level = self.getLevel();
        FluidStack drained = CookeryCopperTapSupport.tryFillBelow(level, targetPos, source);
        if (!drained.isEmpty() && level != null) {
            this.renderingFluid = drained.copy();
            level.playSound(null, targetPos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.5F, 0.9F);
            this.sendFillingParticles(targetPos, drained);
            self.notifyUpdate();
            cir.setReturnValue(true);
        }
    }
}
