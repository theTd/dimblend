package dimblend.dragonchase.music;

import dimblend.dragonchase.DimBlendDragonChase;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The twelve chase BGM tracks as individual sound events
 * ({@code dimblend_dragonchase:music.phase_a_1..4} / {@code music.phase_b_1..4} /
 * {@code music.phase_end_1..4}).
 *
 * <p>One event per file on purpose: the server rolls the track index once and every player hears
 * that same track. A single event holding four weighted sounds would let each client pick its own
 * (vanilla {@code MusicManager}'s randomisation), which contradicts the per-chase random pick
 * stored in {@code DragonChaseSavedData} and makes the choice invisible in logs and commands.
 *
 * <p>The matching {@code assets/dimblend_dragonchase/sounds.json} marks every entry
 * {@code "stream": true}: the tracks are minutes long, so they must not be decoded into a complete
 * PCM buffer up front. Streaming plus the client's {@code looping = true} gives a seamless
 * single-track loop; the Phase End table is the exception, its client instance runs
 * {@code looping = false} and plays its file exactly once.
 */
public final class DragonChaseSounds {
    private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, DimBlendDragonChase.MODID);

    private static final List<DeferredHolder<SoundEvent, SoundEvent>> PHASE_A = List.of(
            sound("music.phase_a_1"),
            sound("music.phase_a_2"),
            sound("music.phase_a_3"),
            sound("music.phase_a_4"));

    private static final List<DeferredHolder<SoundEvent, SoundEvent>> PHASE_B = List.of(
            sound("music.phase_b_1"),
            sound("music.phase_b_2"),
            sound("music.phase_b_3"),
            sound("music.phase_b_4"));

    /**
     * The victory epilogue, one track per Phase A track and the same author in that slot
     * ({@link ChaseMusicMath#phaseEndTrack}).
     */
    private static final List<DeferredHolder<SoundEvent, SoundEvent>> PHASE_END = List.of(
            sound("music.phase_end_1"),
            sound("music.phase_end_2"),
            sound("music.phase_end_3"),
            sound("music.phase_end_4"));

    /** Registers the twelve sound events; called once from the mod constructor. */
    public static void register(IEventBus modEventBus) {
        SOUND_EVENTS.register(modEventBus);
    }

    /**
     * The sound event for one phase and track index.
     *
     * @param phase an audible phase; {@link ChaseMusicPhase#IDLE} has no track
     * @return the bound event, or null for {@code IDLE}
     */
    public static SoundEvent track(ChaseMusicPhase phase, int track) {
        List<DeferredHolder<SoundEvent, SoundEvent>> table = switch (phase) {
            case PHASE_A -> PHASE_A;
            case PHASE_B -> PHASE_B;
            case PHASE_END -> PHASE_END;
            case IDLE -> null;
        };
        return table == null ? null : table.get(ChaseMusicMath.clampTrack(track)).get();
    }

    /** The registry path of one track, e.g. {@code music.phase_a_3} (delegates to {@link ChaseMusicMath#trackPath}). */
    public static String trackPath(ChaseMusicPhase phase, int track) {
        return ChaseMusicMath.trackPath(phase, track);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String path) {
        return SOUND_EVENTS.register(path, () -> SoundEvent.createVariableRangeEvent(DimBlendDragonChase.id(path)));
    }

    private DragonChaseSounds() {
    }
}
