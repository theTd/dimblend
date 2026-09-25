package dimblend.mixin.twilight;

import dimblend.DimBlendRegistries;
import dimblend.compat.TwilightBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.block.TFPortalBlock;
import twilightforest.config.TFConfig;
import twilightforest.init.TFDimension;
import twilightforest.world.TFTeleporter;

@Mixin(value = TFPortalBlock.class, remap = false)
public abstract class TFPortalBlockMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void dimblend$reroute(
            ServerLevel level,
            Entity entity,
            BlockPos pos,
            CallbackInfoReturnable<DimensionTransition> cir
    ) {
        ResourceKey<Level> origin = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(TFConfig.originDimension));
        ResourceKey<Level> destinationKey;
        BlockPos destinationPos = pos;
        if (TwilightBand.isRotating(level)) {
            destinationKey = origin;
        } else if (level.dimension().equals(TFDimension.DIMENSION_KEY)) {
            destinationKey = origin;
        } else {
            destinationKey = DimBlendRegistries.ROTATING_LEVEL;
        }
        ServerLevel destination = level.getServer().getLevel(destinationKey);
        if (destination == null) {
            return;
        }
        if (destinationKey == DimBlendRegistries.ROTATING_LEVEL) {
            destinationPos = TwilightBand.landingInTwilight(destination, pos.getX(), pos.getZ());
        } else {
            WorldBorder border = destination.getWorldBorder();
            double scale = DimensionType.getTeleportationScale(level.dimensionType(), destination.dimensionType());
            destinationPos = border.clampToBounds(pos.getX() * scale, pos.getY(), pos.getZ() * scale);
        }
        cir.setReturnValue(TFTeleporter.createTransition(entity, destination, destinationPos, false));
    }
}
