package dimblend.radio.mixin.client;

import com.mojang.blaze3d.audio.ListenerTransform;
import dimblend.radio.client.RadioAcousticController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla updates its sound listener before mouse processing; publish the camera actually rendered. */
@Mixin(GameRenderer.class)
public abstract class RadioCameraMixin {
    @Shadow @Final private Camera mainCamera;

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            shift = At.Shift.AFTER))
    private void dimblend$currentListener(DeltaTracker tracker, CallbackInfo ci) {
        Camera camera = mainCamera;
        if (camera.isInitialized()) {
            RadioAcousticController.frameRefresh(Minecraft.getInstance(), new ListenerTransform(camera.getPosition(),
                    new Vec3(camera.getLookVector()), new Vec3(camera.getUpVector())));
        }
    }
}
