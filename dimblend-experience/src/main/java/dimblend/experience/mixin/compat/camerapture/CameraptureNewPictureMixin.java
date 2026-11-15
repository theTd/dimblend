package dimblend.experience.mixin.compat.camerapture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.camerapture.CameraFilmRules;
import java.util.function.BiConsumer;
import me.chrr.camerapture.Camerapture;
import me.chrr.camerapture.net.NetworkAdapter;
import me.chrr.camerapture.net.serverbound.NewPicturePacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces CameraCapture's {@code NewPicturePacket} handler at registration time.
 *
 * <p>The upstream handler checks and removes inventory paper before reserving the picture UUID.
 * Film lives in the camera's durability instead, so the handler is wrapped here (rather than by
 * targetting a synthetic lambda name): {@link CameraFilmRules#handleNewPicture} applies the same
 * shutter/cooldown/stat/upload-request sequence with a durability shot and records the capture
 * position for the reserved UUID. The other two registrations are passed through untouched.
 *
 * <p>This is the first {@code onReceiveFromClient} call in the pinned CameraCapture 1.10.12
 * {@code registerPacketHandlers}; {@code require}/{@code allow} pin exactly that one call.
 */
@Mixin(value = Camerapture.class, remap = false)
public abstract class CameraptureNewPictureMixin {
    @WrapOperation(
            method = "registerPacketHandlers",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/chrr/camerapture/net/NetworkAdapter;onReceiveFromClient(Ljava/lang/Class;Ljava/util/function/BiConsumer;)V",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private static void dimblend$replaceNewPictureHandler(
            NetworkAdapter network,
            Class<?> packetType,
            BiConsumer<?, ServerPlayer> handler,
            Operation<Void> original) {
        if (packetType == NewPicturePacket.class) {
            BiConsumer<Object, ServerPlayer> replacement =
                    (packet, player) -> CameraFilmRules.handleNewPicture(player);
            original.call(network, packetType, replacement);
            return;
        }
        original.call(network, packetType, handler);
    }
}
