package dimblend.radio.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.nio.ByteBuffer;
import dimblend.radio.client.RadioAudioStream;
import dimblend.radio.client.RadioStreamPump;
import dimblend.radio.client.RadioStreamBuffering;
import dimblend.radio.acoustics.SteamRenderer;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.SOFTDirectChannels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public abstract class RadioStreamingChannelMixin {
    @Shadow @Final private int source;
    @Shadow private int streamingBufferSize;
    @Shadow private AudioStream stream;
    @Shadow public abstract void disableAttenuation();
    @Shadow public abstract boolean stopped();
    @Shadow public abstract void play();
    @Shadow private void pumpBuffers(int count) { throw new AssertionError(); }
    @Unique private boolean dimblend$explicitlyStopped;
    @Unique private boolean dimblend$refilled;
    @Unique private RadioStreamBuffering.State dimblend$buffering;
    @Unique private int dimblend$starvations;

    // Start short. An actual underrun raises this channel's depth before it restarts.
    @ModifyArg(method = "attachBufferStream", index = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Channel;pumpBuffers(I)V"))
    private int dimblend$shallowQueue(int count) {
        if (!(stream instanceof RadioAudioStream radio) || !radio.simulated()) return count;
        if (dimblend$buffering == null) dimblend$buffering = RadioStreamBuffering.forPlayback(stream.getFormat().getSampleRate());
        return dimblend$buffering.target();
    }

    @Inject(method = "attachBufferStream", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Channel;pumpBuffers(I)V"))
    private void dimblend$acousticStream(AudioStream stream, CallbackInfo ci) {
        if (stream instanceof RadioAudioStream radio && radio.simulated()) {
            streamingBufferSize = SteamRenderer.FRAME * stream.getFormat().getFrameSize();
            // Stereo is spatialized by Steam Audio, which also owns direct and reflected attenuation.
            disableAttenuation();
            // The PCM is already binaural (or panned): with OpenAL Soft HRTF enabled, virtual
            // speakers would filter it through a second set of HRTFs. Play the channels as-is.
            if (AL10.alIsExtensionPresent("AL_SOFT_direct_channels")) {
                AL10.alSourcei(source, SOFTDirectChannels.AL_DIRECT_CHANNELS_SOFT, AL10.AL_TRUE);
            }
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

    // Vanilla refills exactly what played, so headroom added after an underrun would stay forever.
    @ModifyArg(method = "updateStream", index = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Channel;pumpBuffers(I)V"))
    private int dimblend$adaptiveRefill(int played) {
        if (!(stream instanceof RadioAudioStream radio) || !radio.simulated()) return played;
        if (dimblend$buffering == null) dimblend$buffering = RadioStreamBuffering.forPlayback(stream.getFormat().getSampleRate());
        return dimblend$buffering.refill(played, AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED));
    }

    @ModifyExpressionValue(method = "pumpBuffers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/AudioStream;read(I)Ljava/nio/ByteBuffer;"))
    private ByteBuffer dimblend$rememberRefill(ByteBuffer pcm) {
        dimblend$refilled |= pcm != null && pcm.hasRemaining();
        return pcm;
    }

    @Inject(method = "updateStream", at = @At("TAIL"))
    private void dimblend$recoverUnderrun(CallbackInfo ci) {
        // Refilled OpenAL streams remain stopped after starvation. Recover before ChannelAccess retires them.
        if (!dimblend$explicitlyStopped && stream instanceof RadioAudioStream radio && radio.simulated()
                && stopped() && dimblend$refilled) {
            if (dimblend$buffering == null) dimblend$buffering = RadioStreamBuffering.forPlayback(stream.getFormat().getSampleRate());
            int target = dimblend$buffering.underrun();
            int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
            // Restarting with the same shallow queue can cause an endless play/starve/play cycle.
            if (queued < target) pumpBuffers(target - queued);
            int count = ++dimblend$starvations;
            if (count <= 3 || (count & (count - 1)) == 0) {
                dimblend.radio.DimBlendRadio.LOGGER.warn("[radio] audio starvation #{}; recovered with {} buffers ({} ms)",
                        count, target, Math.round(target * SteamRenderer.FRAME * 1000f / stream.getFormat().getSampleRate()));
            }
            play();
        }
    }
}
