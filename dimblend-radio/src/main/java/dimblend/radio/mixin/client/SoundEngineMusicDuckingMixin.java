package dimblend.radio.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.radio.client.RadioAudibility;
import dimblend.radio.client.RadioMusicDucking;
import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply the radio envelope after every manager's own music gain and the player's slider. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMusicDuckingMixin {
    @Shadow @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Shadow
    private float calculateVolume(SoundInstance sound) { throw new AssertionError(); }

    @Unique
    private boolean dimblend$refreshMusic;

    @Inject(method = "tickNonPaused()V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$tickMusicDucking(CallbackInfo ci) {
        this.dimblend$refreshMusic = RadioMusicDucking.tick(RadioAudibility.anyAudible());
    }

    @ModifyReturnValue(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at = @At("RETURN"), require = 1, allow = 1)
    private float dimblend$duckMusicUpdates(float volume, SoundInstance sound) {
        return RadioMusicDucking.volume(sound, volume);
    }

    // play() uses the float/category overload directly, bypassing the instance overload above.
    @ModifyExpressionValue(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"),
            require = 1, allow = 1)
    private float dimblend$duckNewMusic(float volume, SoundInstance sound) {
        return RadioMusicDucking.volume(sound, volume);
    }

    @Inject(method = "tickNonPaused()V", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$refreshNonTickingMusic(CallbackInfo ci) {
        if (!this.dimblend$refreshMusic) return;
        this.instanceToChannel.forEach((sound, channel) -> {
            // Tickable mod music already receives the envelope through calculateVolume each tick.
            if (!(sound instanceof TickableSoundInstance) && RadioMusicDucking.affects(sound)) {
                float volume = this.calculateVolume(sound);
                channel.execute(source -> source.setVolume(volume));
            }
        });
    }

    @Inject(method = "stopAll()V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$resetMusicDucking(CallbackInfo ci) {
        RadioMusicDucking.reset();
    }
}
