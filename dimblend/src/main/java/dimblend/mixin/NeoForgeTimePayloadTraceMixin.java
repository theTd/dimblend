package dimblend.mixin;

import dimblend.client.ClientSkyTimeTrace;
import net.neoforged.neoforge.network.payload.ClientboundCustomSetTimePayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client distribution only; integrated-server construction and network decoding are both observable. */
@Mixin(ClientboundCustomSetTimePayload.class)
public abstract class NeoForgeTimePayloadTraceMixin {
    @Inject(method = "<init>(JJZFF)V", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$tracePayload(long gameTime, long dayTime, boolean gameRule, float fraction, float perTick,
            CallbackInfo ci) {
        ClientSkyTimeTrace.neoForgePayload(gameTime, dayTime, gameRule, fraction, perTick);
    }
}
