package dimblend.dragonchase.client.music;

import dimblend.dragonchase.music.ChaseMusicMath;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * One chase BGM track: a non-positional, looping, streaming sound on {@link SoundSource#RECORDS}
 * whose volume is owned by its own fade.
 *
 * <p>Choices, each with its reason:
 * <ul>
 *   <li><b>RECORDS, not MUSIC</b> — the instance's category volume multiplies into the audible
 *       volume, and this pack runs with the Music slider at 0 (the sound engine stops any MUSIC
 *       instance whose category volume is zero, {@code canStartSilent} notwithstanding). RECORDS is
 *       what the radio already uses and is audible with the pack's defaults. The controller blocks
 *       every MUSIC sound instead, so the chase never plays over vanilla/Aether music.</li>
 *   <li><b>Relative, at the origin, {@code Attenuation.NONE}</b> — a dimension-wide BGM must not
 *       attenuate with distance, and coordinates tied to a sub-level would let sable's
 *       {@code MovingSoundInstanceDelegate} wrap the instance (which breaks {@code isActive}
 *       lookups).</li>
 *   <li><b>{@code canStartSilent} and a non-zero first volume</b> — a crossfaded-in track starts at
 *       {@link ChaseMusicMath#SILENT_START_VOLUME}, so the eager zero-volume skip is not reached
 *       and the fade-in runs from the very first tick.</li>
 *   <li><b>{@code looping}</b> — with {@code "stream": true} the engine feeds a
 *       {@code LoopingAudioStream} when the instance loops, so the same file restarts seamlessly
 *       at its end. The Phase End track passes {@code false} instead: the engine then feeds a
 *       {@code FiniteAudioStream} and the file plays exactly once.</li>
 * </ul>
 *
 * <p>Volume is advanced in {@link #tick()}, which the sound engine calls once per client tick and
 * reads back into the channel in the same tick (so a fade is audible immediately). A paused game
 * does not tick tickable sounds, which freezes a fade exactly as long as the audio is paused.
 */
public final class ChaseMusicSoundInstance extends AbstractTickableSoundInstance {
    private float fadeFrom;
    private float fadeTo;
    private int fadeTicks;
    private int fadeElapsed;
    private boolean fadeFinished;

    /**
     * @param sound        the track's event
     * @param startVolume  the volume of the first tick
     * @param targetVolume the volume the linear fade ends on; {@code 0} stops the instance at the end
     * @param ticks        fade length in client ticks; {@code 0} applies the target immediately
     * @param looping      true for the fight tracks (seamless restart at the end of the file),
     *                     false for the one-shot Phase End track
     */
    public ChaseMusicSoundInstance(SoundEvent sound, float startVolume, float targetVolume, int ticks,
            boolean looping) {
        super(sound, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.volume = Math.max(0.0F, Math.min(1.0F, startVolume));
        this.pitch = 1.0F;
        this.looping = looping;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.delay = 0;
        this.fadeFrom = this.volume;
        this.fadeTo = Math.max(0.0F, Math.min(1.0F, targetVolume));
        this.fadeTicks = Math.max(0, ticks);
        this.fadeElapsed = 0;
        if (this.fadeTicks == 0) {
            finishFade();
        }
    }

    /** Re-targets the fade from the current volume, e.g. when a chase ends mid-crossfade. */
    public void fadeTo(float targetVolume, int ticks) {
        this.fadeFrom = this.volume;
        this.fadeTo = Math.max(0.0F, Math.min(1.0F, targetVolume));
        this.fadeTicks = Math.max(1, ticks);
        this.fadeElapsed = 0;
        this.fadeFinished = false;
    }

    /** Stops without a fade (a chase restarting, a player leaving the dimension). */
    public void stopNow() {
        if (!this.isStopped()) {
            stop();
        }
    }

    /** True once the fade reached its target (a fade to zero has stopped the instance). */
    public boolean isFadeFinished() {
        return this.fadeFinished || this.isStopped();
    }

    @Override
    public void tick() {
        if (this.fadeFinished || this.isStopped()) {
            return;
        }
        this.fadeElapsed++;
        this.volume = ChaseMusicMath.fadeGain(this.fadeFrom, this.fadeTo, this.fadeElapsed, this.fadeTicks);
        if (this.fadeElapsed >= this.fadeTicks) {
            finishFade();
        }
    }

    private void finishFade() {
        this.volume = this.fadeTo;
        this.fadeFinished = true;
        if (this.fadeTo <= 0.0F) {
            stop();
        }
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
