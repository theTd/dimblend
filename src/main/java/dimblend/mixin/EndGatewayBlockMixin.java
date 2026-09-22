package dimblend.mixin;

import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables wild end gateways inside the rotating dimension.
 *
 * <p>A naturally generated {@code minecraft:end_gateway} in an End band carries an exit of
 * {@code (100, 50, 0)} with {@code exact=true} (vanilla {@code end_gateway_return}), so without
 * this patch stepping in teleports the player across the rotating dimension to whatever happens
 * to be at the origin band's (100, 50, 0) — possibly inside solid terrain. Cancel both halves of
 * the vanilla chain: {@code entityInside} (which would arm the portal + start the cooldown) and
 * {@code getPortalDestination} (the safety net resolving the actual destination).</p>
 */
@Mixin(EndGatewayBlock.class)
public abstract class EndGatewayBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void dimblend$blockWildGatewayInRotating(
            BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (!level.isClientSide && level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            ci.cancel();
        }
    }

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void dimblend$noWildGatewayDestinationInRotating(
            ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<DimensionTransition> cir) {
        if (level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            cir.setReturnValue(null);
        }
    }
}
