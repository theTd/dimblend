package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import twilightforest.client.event.ClientGameEvents;

@Mixin(value = ClientGameEvents.class, remap = false)
public abstract class ClientGameEventsMixin {
    @Inject(method = "killVignette", at = @At("TAIL"))
    private void dimblend$killVignetteInBand(RenderFrameEvent.Pre event, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null
                && minecraft.player != null
                && TwilightBand.isTwilightPos(minecraft.level, minecraft.player.blockPosition())) {
            minecraft.gui.vignetteBrightness = 0.0F;
        }
    }

    /**
     * TF's aurora quad is authored at world Y=256 (the real TF dimension top).
     * Rotating TF terrain is lifted +64, so the sheet must move with it or it
     * sits 64 blocks closer to the player and looks oversized.
     */
    @ModifyConstant(method = "renderAurora", constant = @Constant(doubleValue = 256.0))
    private double dimblend$liftAuroraSheet(double original) {
        Minecraft minecraft = Minecraft.getInstance();
        return TwilightBand.liftedEffectY(
                original, minecraft.level != null && TwilightBand.isRotating(minecraft.level));
    }
}
