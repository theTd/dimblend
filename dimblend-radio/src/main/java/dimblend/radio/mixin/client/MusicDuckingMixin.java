package dimblend.radio.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.client.RadioMusicDucking;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;

/**
 * Keep vanilla's scheduling policy while SoundEngine applies the shared background-music fade.
 * Mod-owned music channels are controlled at the sound engine rather than through currentMusic.
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
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$duckWhileRadioAudible(CallbackInfo ci) {
        boolean audible = RadioMusicDucking.suppressing();
        float gain = RadioMusicDucking.gain();
        if (this.currentMusic != null && (audible || gain < 1.0f)
                && (gain == 0.0f || !this.minecraft.getSoundManager().isActive(this.currentMusic))) {
            this.minecraft.getSoundManager().stop(this.currentMusic);
            this.currentMusic = null;
        }
        if (audible || (this.currentMusic != null && gain < 1.0f)) {
            this.nextSongDelay = 100;
            ci.cancel();
        }
        if (audible != this.dimblend$wasAudible) {
            DimBlendRadio.LOGGER.info("[radio] bgm ducking {}", audible ? "on" : "off");
        }
        this.dimblend$wasAudible = audible;
    }
}
