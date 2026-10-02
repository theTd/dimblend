package dimblend.radio.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.nio.ByteBuffer;
import dimblend.radio.client.RadioAudioStream;
import dimblend.radio.client.RadioStreamPump;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public abstract class RadioStreamingChannelMixin {
    @Shadow private int streamingBufferSize;
    @Shadow private AudioStream stream;
    @Shadow public abstract void disableAttenuation();
    @Shadow public abstract boolean stopped();
    @Shadow public abstract void play();
    @Unique private boolean dimblend$explicitlyStopped;
    @Unique private boolean dimblend$refilled;

    // Two 2048-frame buffers (~93ms total) instead of four: halves the end-to-end pipeline
    // latency of the acoustic path so head-turns and movement track faster.
    @ModifyArg(method = "attachBufferStream", index = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Channel;pumpBuffers(I)V"))
    private int dimblend$shallowQueue(int count) {
        return stream instanceof RadioAudioStream radio && radio.simulated() ? 2 : count;
    }

    @Inject(method = "attachBufferStream", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Channel;pumpBuffers(I)V"))
    private void dimblend$acousticStream(AudioStream stream, CallbackInfo ci) {
        if (stream instanceof RadioAudioStream radio && radio.simulated()) {
            // 2048 frames = one native render block (~46ms): keeps end-to-end pipeline latency low.
            streamingBufferSize = 2048 * stream.getFormat().getFrameSize();
            // Stereo is spatialized by Steam Audio, which also owns direct and reflected attenuation.
            disableAttenuation();
            RadioStreamPump.register((Channel) (Object) this);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void dimblend$stopRefills(CallbackInfo ci) {
        RadioStreamPump.unregister((Channel) (Object) this);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void dimblend$rememberStop(CallbackInfo ci) {
        dimblend$explicitlyStopped = true;
    }

    @Inject(method = "play", at = @At("HEAD"))
    private void dimblend$rememberPlay(CallbackInfo ci) {
        dimblend$explicitlyStopped = false;
    }

    @Inject(method = "updateStream", at = @At("HEAD"))
    private void dimblend$beginRefill(CallbackInfo ci) {
        dimblend$refilled = false;
    }

    @ModifyExpressionValue(method = "pumpBuffers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/AudioStream;read(I)Ljava/nio/ByteBuffer;"))
    private ByteBuffer dimblend$rememberRefill(ByteBuffer pcm) {
        dimblend$refilled |= pcm != null && pcm.hasRemaining();
        return pcm;
    }

    @Unique private static final java.util.concurrent.atomic.AtomicInteger dimblend$underruns
            = new java.util.concurrent.atomic.AtomicInteger();

    @Inject(method = "updateStream", at = @At("TAIL"))
    private void dimblend$recoverUnderrun(CallbackInfo ci) {
        // Refilled OpenAL streams remain stopped after starvation. Recover before ChannelAccess retires them.
        if (!dimblend$explicitlyStopped && stream instanceof RadioAudioStream radio && radio.simulated()
                && stopped() && dimblend$refilled) {
            if (Boolean.getBoolean("dimblend.radio.acoustic.debug")) {
                dimblend.radio.DimBlendRadio.LOGGER.info("[radio] underrun recovered #{}",
                        dimblend$underruns.incrementAndGet());
            }
            play();
        }
    }
}
