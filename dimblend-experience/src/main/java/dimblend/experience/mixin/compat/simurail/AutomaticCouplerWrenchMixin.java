package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dimblend.experience.compat.simurail.CouplerCharge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * E10 扳手右键改为「充能 / 取消充能」（用户口径，取代 Simurail 的换类型）。
 *
 * <p>Simurail 的 {@code onWrenched} 原本调 {@code cycleType}（詹式 ⇄ 柴田）。
 * 本 mixin 在其 HEAD 接管：切换充能态、播原生转轴音效、返回
 * {@code InteractionResult.SUCCESS}，原生换类型动作不再执行。类型本身已被
 * {@code AutomaticCouplerChargeMixin} 钉死为詹式，两处一致。</p>
 *
 * <p>空手右键（{@code useItemOn} → {@code cycleLength}，改车钩长短）不在本条口径里，
 * 保持 Simurail 原样。</p>
 */
@Mixin(AutomaticCouplerBlock.class)
public abstract class AutomaticCouplerWrenchMixin {

    @Inject(method = "onWrenched", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$wrenchTogglesCharge(BlockState state, UseOnContext context,
            CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!CouplerCharge.toggleCharge(level, pos)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        IWrenchable.playRotateSound(level, pos);
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
