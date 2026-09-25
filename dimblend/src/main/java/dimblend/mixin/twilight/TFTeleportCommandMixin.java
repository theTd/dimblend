package dimblend.mixin.twilight;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dimblend.compat.TwilightBand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.command.TFTeleportCommand;

@Mixin(value = TFTeleportCommand.class, remap = false)
public abstract class TFTeleportCommandMixin {
    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void dimblend$tpToRotating(
            CommandContext<CommandSourceStack> ctx,
            CallbackInfoReturnable<Integer> cir
    ) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel rotating = TwilightBand.rotating(source.getServer());
        if (rotating == null) {
            return;
        }
        BlockPos landing = TwilightBand.landingInTwilight(rotating, player.getBlockX(), player.getBlockZ());
        if (!rotating.isInWorldBounds(landing)) {
            return;
        }
        player.teleportTo(
                rotating,
                landing.getX() + 0.5,
                landing.getY(),
                landing.getZ() + 0.5,
                java.util.Set.of(),
                player.getYRot(),
                player.getXRot()
        );
        source.sendSuccess(
                () -> Component.translatable(
                        "commands.tffeature.teleport.success",
                        String.format(java.util.Locale.ROOT, "%.1f", landing.getX() + 0.5),
                        String.format(java.util.Locale.ROOT, "%.1f", (double) landing.getY()),
                        String.format(java.util.Locale.ROOT, "%.1f", landing.getZ() + 0.5)
                ),
                false
        );
        cir.setReturnValue(1);
    }
}
