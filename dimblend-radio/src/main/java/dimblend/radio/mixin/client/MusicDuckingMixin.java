package dimblend.radio.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.client.RadioAudibility;
import dimblend.radio.client.RadioMusicFade;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;

/**
 * Fade the current vanilla music channel to silence over 20 client ticks, then stop it.
 * Suppress MusicManager.tick while radio is audible to prevent vanilla from restarting music.
 * A takeover cancelled mid-fade restores the same channel smoothly.
 */
@Mixin(MusicManager.class)
public abstract class MusicDuckingMixin {
    @Shadow
    private SoundInstance currentMusic;

    @Shadow
    private int nextSongDelay;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private boolean dimblend$wasAudible;
    @Unique
    private final RadioMusicFade dimblend$fade = new RadioMusicFade();
    @Unique
    private SoundInstance dimblend$fadingMusic;

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void dimblend$duckWhileRadioAudible(CallbackInfo ci) {
        boolean audible = RadioAudibility.anyAudible();
        if (this.currentMusic != this.dimblend$fadingMusic) {
            this.dimblend$fadingMusic = this.currentMusic;
            this.dimblend$fade.reset();
        }
        if (this.currentMusic != null && (audible || this.dimblend$fade.gain() < 1.0f)) {
            float gain = this.dimblend$fade.tick(audible);
            if (gain == 0.0f || !this.minecraft.getSoundManager().isActive(this.currentMusic)) {
                this.minecraft.getSoundManager().stop(this.currentMusic);
                this.currentMusic = null;
            } else {
                // Modify only this channel; never change the user's MUSIC or MASTER settings.
                var engine = (SoundEngineAccessor) ((SoundManagerAccessor) this.minecraft.getSoundManager())
                        .dimblend$radioSoundEngine();
                var channel = engine.dimblend$radioChannels().get(this.currentMusic);
                if (channel != null) {
                    float volume = engine.dimblend$radioVolume(this.currentMusic) * gain;
                    channel.execute(source -> source.setVolume(volume));
                }
            }
        }
        if (audible || (this.currentMusic != null && this.dimblend$fade.gain() < 1.0f)) {
            this.nextSongDelay = 100;
            ci.cancel();
        }
        if (audible != this.dimblend$wasAudible) {
            DimBlendRadio.LOGGER.info("[radio] bgm ducking {}", audible ? "on" : "off");
        }
        this.dimblend$wasAudible = audible;
    }
}
