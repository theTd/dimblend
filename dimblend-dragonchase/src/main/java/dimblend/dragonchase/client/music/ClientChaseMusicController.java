package dimblend.dragonchase.client.music;

import dimblend.DimBlendRegistries;
import dimblend.api.DragonChaseClientState;
import dimblend.dragonchase.music.ChaseMusicMath;
import dimblend.dragonchase.music.ChaseMusicPhase;
import dimblend.dragonchase.music.DragonChaseSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side owner of the chase BGM: which track plays, the fade curves, and the blanket block on
 * every {@link SoundSource#MUSIC} sound while the BGM is audible or fading out.
 *
 * <p>The server only says "phase + track" ({@link dimblend.dragonchase.music.ChaseMusicStatePayload});
 * everything audible is decided here, because only the client knows what it is currently playing:
 * <ul>
 *   <li><b>Phase A/B start</b> — a fresh track fades in from {@link ChaseMusicMath#SILENT_START_VOLUME}
 *       over 4 s. If the payload asks for a crossfade while a track is already playing, the old one
 *       fades out over the same 4 s while the new one fades in (two streaming channels, well inside
 *       the 8-channel streaming pool).</li>
 *   <li><b>Phase End</b> — the victory epilogue after the kill: the same 4 s fade-in, but a
 *       <b>non-looping</b> instance ({@link ChaseMusicPhase#isLooping()} {@code == false}) that
 *       must play its file exactly once. The chase itself is over, so {@link DragonChaseClientState}
 *       is already {@code false}; only {@code blockingMusic} stays on until the stream ends.</li>
 *   <li><b>IDLE</b> — the playing track fades out over 16 s, then stops and releases the MUSIC
 *       block.</li>
 *   <li><b>MUSIC block</b> — every client tick while audible/fading calls
 *       {@code MusicManager.stopPlaying()} and {@code SoundManager.stop(null, MUSIC)}, and a
 *       {@link PlaySoundEvent} listener at {@link EventPriority#LOWEST} nulls any MUSIC instance
 *       that still reaches the engine (vanilla, Aether's own manager, or any mod). LOWEST runs last,
 *       so it overrides every earlier canceller instead of being overridden.</li>
 * </ul>
 *
 * <p>Leaving {@code dimblend:rotating} (or logging out) hard-resets: tracks stop immediately and the
 * MUSIC block lifts, so a lost payload can never leave the player in a permanent silent-music state.
 * {@link DragonChaseClientState} follows the same transitions ({@code true} on Phase A/B only,
 * {@code false} on Phase End — the chase is won — and once the fade-out starts).
 */
public final class ClientChaseMusicController {
    /** Tracks that are fading out; at most one during a crossfade, plus the 16 s end fade. */
    private static final List<ChaseMusicSoundInstance> RETIRING = new ArrayList<>();

    private static ChaseMusicPhase phase = ChaseMusicPhase.IDLE;
    private static int trackIndex = -1;
    private static ChaseMusicSoundInstance active;
    private static boolean blockingMusic;

    /** Wiring line for {@code DragonChaseClient#init}: registers the tick, sound and logout hooks. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ClientChaseMusicController::onPlaySound);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> reset());
    }

    /** The server's phase/track decision, already on the client thread. */
    public static void accept(ChaseMusicPhase newPhase, int track, boolean crossfade) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return;
        }
        if (!newPhase.isAudible()) {
            beginEndFade();
            return;
        }
        beginPhase(minecraft, newPhase, ChaseMusicMath.clampTrack(track), crossfade);
    }

    private static void beginPhase(Minecraft minecraft, ChaseMusicPhase newPhase, int track, boolean crossfade) {
        if (newPhase == phase && track == trackIndex && active != null && !active.isStopped()) {
            // A duplicate (login sync, dimension change) must not restart the track from zero.
            return;
        }
        blockMusicNow(minecraft);
        boolean overlap = crossfade && phase.isAudible() && active != null && !active.isStopped();
        if (overlap) {
            active.fadeTo(0.0F, ChaseMusicMath.CROSSFADE_TICKS);
            RETIRING.add(active);
        } else {
            if (active != null) {
                active.stopNow();
            }
            for (ChaseMusicSoundInstance instance : RETIRING) {
                instance.stopNow();
            }
            RETIRING.clear();
        }
        active = null;

        phase = newPhase;
        trackIndex = track;
        blockingMusic = true;
        // The chase is "active" only while the dragon is still being chased: Phase End arrives after
        // the kill, where the train is no longer the boss bar's business. The MUSIC block stays on
        // regardless, so vanilla/Aether music cannot cut in between the fight and its epilogue.
        DragonChaseClientState.setChaseActive(newPhase == ChaseMusicPhase.PHASE_A
                || newPhase == ChaseMusicPhase.PHASE_B);

        SoundEvent event = DragonChaseSounds.track(newPhase, track);
        if (event == null) {
            return;
        }
        active = new ChaseMusicSoundInstance(event, ChaseMusicMath.SILENT_START_VOLUME, 1.0F,
                ChaseMusicMath.CROSSFADE_TICKS, newPhase.isLooping());
        minecraft.getSoundManager().play(active);
    }

    private static void beginEndFade() {
        if (active == null && RETIRING.isEmpty()) {
            phase = ChaseMusicPhase.IDLE;
            trackIndex = -1;
            blockingMusic = false;
            DragonChaseClientState.setChaseActive(false);
            return;
        }
        if (active != null) {
            RETIRING.add(active);
            active = null;
        }
        // Every still-audible instance is retargeted, so a kill during the 4 s crossfade still gets
        // the full 16 s from its current volume instead of the short remaining crossfade.
        for (ChaseMusicSoundInstance instance : RETIRING) {
            instance.fadeTo(0.0F, ChaseMusicMath.END_FADE_TICKS);
        }
        phase = ChaseMusicPhase.IDLE;
        trackIndex = -1;
        blockingMusic = true;
        DragonChaseClientState.setChaseActive(false);
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            reset();
            return;
        }
        dropFinishedOneShot(minecraft);
        dropFinishedFades();
        if (blockingMusic) {
            blockMusicNow(minecraft);
        }
    }

    /**
     * Releases a one-shot track whose file ran out on its own (Phase End).
     *
     * <p>Do not wait for {@code isStopped()}: vanilla {@code SoundEngine#tickNonPaused}
     * ({@code SoundEngine.java:267-286}, mcsrc 1.21.1) removes a naturally finished tickable sound
     * from {@code instanceToChannel}, {@code soundDeleteTime} and {@code tickingSounds} but
     * <b>never calls {@code sound.stop()}</b>, so {@code isStopped()} stays {@code false} forever.
     * The only reliable signal is {@code SoundManager#isActive} (same file {@code l.318-326}), which
     * answers {@code false} once both maps have dropped the instance.
     *
     * <p>Clearing {@link #active} and the phase here lets the very next {@link #dropFinishedFades()}
     * restore {@code blockingMusic = false}, so vanilla/Aether music can schedule again instead of
     * being muted until the player leaves the dimension. Skipped while paused: tickable sounds are
     * frozen then, and a stream that has not started or finished yet must not be torn down.
     */
    private static void dropFinishedOneShot(Minecraft minecraft) {
        if (active == null || active.isLooping() || minecraft.isPaused()) {
            return;
        }
        if (!active.isStopped() && minecraft.getSoundManager().isActive(active)) {
            return;
        }
        active.stopNow();
        active = null;
        phase = ChaseMusicPhase.IDLE;
        trackIndex = -1;
    }

    private static void dropFinishedFades() {
        Iterator<ChaseMusicSoundInstance> iterator = RETIRING.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isFadeFinished()) {
                iterator.remove();
            }
        }
        if (blockingMusic && phase == ChaseMusicPhase.IDLE && active == null && RETIRING.isEmpty()) {
            // The 16 s fade-out has finished: vanilla/Aether music may schedule again.
            blockingMusic = false;
        }
    }

    /** True while the chase BGM is audible or fading out, i.e. while every MUSIC sound is blocked. */
    public static boolean isBlockingMusic() {
        return blockingMusic;
    }

    private static void onPlaySound(PlaySoundEvent event) {
        if (!isBlockingMusic()) {
            return;
        }
        SoundInstance sound = event.getSound();
        if (sound != null && sound.getSource() == SoundSource.MUSIC) {
            event.setSound(null);
        }
    }

    private static void blockMusicNow(Minecraft minecraft) {
        minecraft.getMusicManager().stopPlaying();
        minecraft.getSoundManager().stop(null, SoundSource.MUSIC);
    }

    private static void reset() {
        if (active != null) {
            active.stopNow();
            active = null;
        }
        for (ChaseMusicSoundInstance instance : RETIRING) {
            instance.stopNow();
        }
        RETIRING.clear();
        phase = ChaseMusicPhase.IDLE;
        trackIndex = -1;
        blockingMusic = false;
        DragonChaseClientState.setChaseActive(false);
    }

    private ClientChaseMusicController() {
    }
}
