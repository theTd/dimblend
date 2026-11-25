package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import twilightforest.util.Enforcement;

/**
 * Twilight Forest's progression gate, once per 20 ticks from
 * {@code ProgressionEvents#performProtectionAndPortalChecks}, resolves the restriction for the
 * biome under {@code player.blockPosition()} and applies the curse (darkness, hunger, fire,
 * frosty, acid-rain ticks) plus a structure hint spawn.
 *
 * <p>A player riding a carriage across a locked region keeps the same {@code blockPosition()},
 * so the gate cannot tell rider from pedestrian. While Sable carries them
 * ({@link TwilightBand#isOnSable}) inside {@code dimblend:rotating}, the curse is skipped: the
 * train is the shelter — the same reasoning the shortcut gate applies to standing on a Sable
 * platform.
 *
 * <p>{@code isRotating} keeps the real {@code twilight_forest} dimension untouched.
 */
@Mixin(value = Enforcement.class, remap = false)
public abstract class EnforcementMixin {

    @Inject(method = "enforceBiomeProgression", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void dimblend$skipOnSable(Player player, ServerLevel level, CallbackInfo ci) {
        if (TwilightBand.isRotating(level) && TwilightBand.isOnSable(player)) {
            ci.cancel();
        }
    }
}
