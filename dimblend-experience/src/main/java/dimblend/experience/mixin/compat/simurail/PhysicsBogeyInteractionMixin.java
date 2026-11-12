package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import dimblend.experience.compat.simurail.SimurailBlockGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces {@code DECISIONS.md} #1 on {@code simurail:physics_bogey}:
 * <ul>
 *   <li>Only an empty hand or wrench ({@code create:wrench} / {@code #c:tools/wrench}) opens
 *       {@code PhysicsBogeyMenu}; every other held item passes through to normal item/block
 *       placement.</li>
 *   <li>Wrenches never rotate ({@code onWrenched}) or dismantle ({@code onSneakWrenched}) a
 *       physics bogey, even in Creative mode or via direct {@code IWrenchable} callers.</li>
 * </ul>
 */
@Mixin(PhysicsBogeyBlock.class)
public abstract class PhysicsBogeyInteractionMixin extends HorizontalKineticBlock {

    private PhysicsBogeyInteractionMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$onUseItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        cir.setReturnValue(SimurailBlockGuard.onBogeyUseItemOn(
                this, stack, state, level, pos, player, hand, hitResult));
    }

    @Inject(method = "onSneakWrenched", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$onSneakWrenched(BlockState state, UseOnContext context,
            CallbackInfoReturnable<InteractionResult> cir) {
        cir.setReturnValue(InteractionResult.PASS);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }
}
