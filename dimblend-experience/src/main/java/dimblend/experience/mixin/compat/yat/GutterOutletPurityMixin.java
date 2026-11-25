package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 缺口 4：Create: Fluid 集水口（{@code com.adonis.fluid.block.GutterOutlet.GutterOutletBlockEntity}）
 * 的雨/滴水两条路径纯度（世界水体那条由 {@code GutterDrainerPurityMixin} 覆盖）。
 *
 * <p>javap 核实：该类在 <b>fluid-2.0.1</b> 而不是 Create 里，方法为
 * {@code handlePrecipitationCollection()V} / {@code handleDripstoneCollection()V} /
 * {@code accumulateAndFill(FluidStack, boolean)V}（三条路径最终都走 accumulateAndFill）。</p>
 *
 * <p>口径：雨 = {@code Purity: 1}；钟乳石滴水源头不可靠（滴尖不是水方块），同样按 1。
 * 世界采集路径保持 {@code pending == -1}，保留 Drainer 侧的纯度。</p>
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.block.GutterOutlet.GutterOutletBlockEntity", remap = false)
public abstract class GutterOutletPurityMixin {

    /** 待打标纯度；{@code -1} = 不覆盖。 */
    @Unique
    private int dimblend$pendingPurity = -1;

    @Inject(method = "handlePrecipitationCollection", at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void dimblend$markRainPurity(CallbackInfo ci) {
        this.dimblend$pendingPurity = WaterSourcePurityRules.RAIN_PURITY;
    }

    @Inject(method = "handleDripstoneCollection", at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void dimblend$markDripPurity(CallbackInfo ci) {
        this.dimblend$pendingPurity = WaterSourcePurityRules.RAIN_PURITY;
    }

    @WrapMethod(method = "accumulateAndFill", remap = false, require = 1, allow = 1)
    private void dimblend$stampAccumulatedWater(FluidStack stack, boolean simulate, Operation<Void> original) {
        int pending = this.dimblend$pendingPurity;
        this.dimblend$pendingPurity = -1;
        if (pending >= 0
                && DirtyWaterCompat.purityAvailable()
                && WaterSourcePurityRules.isWater(stack.isEmpty(), stack.is(Fluids.WATER))) {
            DirtyWaterPurity.stampFluidPurity(stack, pending);
        }
        original.call(stack, simulate);
    }
}
