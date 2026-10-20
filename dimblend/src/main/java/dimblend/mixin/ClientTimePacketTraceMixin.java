package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.client.ClientSkyTimeTrace;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public abstract class ClientTimePacketTraceMixin {
    @WrapOperation(method = "handleSetTime", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;setDayTime(J)V"), require = 1, allow = 1)
    private void dimblend$traceTimePacket(ClientLevel level, long time, Operation<Void> original) {
        if (!ClientSkyTimeTrace.enabled()) {
            original.call(level, time);
            return;
        }
        long before = level.getDayTime();
        original.call(level, time);
        ClientSkyTimeTrace.timePacket(level, time, before);
    }
}
