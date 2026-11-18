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
 * 让铜龙头（{@code fluid:copper_tap}）能向正下方的森罗物语厨房汤锅与水壶注入 {@code 1000 mB} 水，
 * 并在源水是 Purity 0 时整次拒收。
 *
 * <p>脏水必须 {@code setReturnValue(false)} 而不是"返回空继续"：原版 {@code tryProcess} 在
 * 我们不打桩时会继续尝试把同一水源注入炼药锅/工作盆/置物台——目标是汤锅却把水倒进旁边，
 * 比"没反应"更糟。拒收发生在抽源罐之前，源与目标都不变。</p>
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
        CookeryCopperTapSupport.TapFill attempt = CookeryCopperTapSupport.tryFillBelow(level, targetPos, source);
        if (attempt.rejectedDirtyWater()) {
            // 目标是汤锅/水壶且水脏：不注入、不抽源，也不让原版把同样的水倒进别的目标。
            cir.setReturnValue(false);
            return;
        }
        if (attempt.filled() && level != null) {
            FluidStack drained = attempt.fluid();
            this.renderingFluid = drained.copy();
            level.playSound(null, targetPos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.5F, 0.9F);
            this.sendFillingParticles(targetPos, drained);
            self.notifyUpdate();
            cir.setReturnValue(true);
        }
    }
}
