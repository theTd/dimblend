package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import twilightforest.client.renderer.TFWeatherRenderer;
import twilightforest.network.StructureProtectionPacket;

@Mixin(value = StructureProtectionPacket.class, remap = false)
public abstract class StructureProtectionPacketMixin {
    @Inject(method = "handle", at = @At("HEAD"))
    private static void dimblend$applyBoxes(StructureProtectionPacket message, IPayloadContext ctx, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && TwilightBand.isRotating(minecraft.level)) {
            ctx.enqueueWork(() -> TFWeatherRenderer.setProtectedBoxes(message.boxes().orElse(null)));
        }
    }
}
