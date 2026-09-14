package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import twilightforest.config.TFConfig;
import twilightforest.events.CapabilityEvents;
import twilightforest.init.TFDataAttachments;
import twilightforest.world.NoReturnTeleporter;
import twilightforest.world.TFTeleporter;

@Mixin(value = CapabilityEvents.class, remap = false)
public abstract class CapabilityEventsMixin {
    @Inject(method = "newSpawnInTwilightForest", at = @At("HEAD"), cancellable = true)
    private static void dimblend$spawnInRotating(ServerPlayer player, CallbackInfo ci) {
        if (!TFConfig.newPlayersSpawnInTF) {
            ci.cancel();
            return;
        }
        ServerLevel rotating = TwilightBand.rotating(player.getServer());
        if (rotating == null) {
            return;
        }
        BlockPos landing = TwilightBand.landingInTwilight(rotating, player.getBlockX(), player.getBlockZ());
        player.changeDimension(TFConfig.portalForNewPlayerSpawn
                ? TFTeleporter.createTransition(player, rotating, landing, true)
                : NoReturnTeleporter.createNoPortalTransition(rotating, player, landing));
        player.setRespawnPosition(rotating.dimension(), landing, player.getYRot(), true, false);
        player.setData(TFDataAttachments.BANISHED_TO_TWILIGHT_FOREST, Unit.INSTANCE);
        ci.cancel();
    }
}
