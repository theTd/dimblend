package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.SubLevelProjection;
import dimblend.radio.acoustics.AcousticAvailability;
import dimblend.radio.acoustics.AcousticSnapshot;
import dimblend.radio.acoustics.AcousticSceneChanges;
import dimblend.radio.acoustics.PathingField;
import dimblend.radio.acoustics.ReflectionMeshCache;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.audio.ListenerTransform;

public final class RadioAcousticController {
    /**
     * Acoustic audibility radius: bound radios carry a linear-to-zero gain curve out to this
     * distance, rendered or panned (the vanilla path keeps RANGE_BLOCKS=64).
     */
    public static final double AUDIBLE_RANGE = 96;
    /** Snapshot slack beyond the simulated radios' mesh regions, so small motion reuses it. */
    private static final double CAPTURE_SLACK = 16;

    private static final class Entry {
        final RadioSimulationSession session;
        boolean selected;
        Entry(RadioSimulationSession session) { this.session = session; }
    }
    private static final Map<RadioInstance, Entry> SESSIONS = new IdentityHashMap<>();
    private static Level level;
    private static AcousticSnapshot snapshot;
    private static long captured;
    private static long capturedRevision;

    public static double audibleRange(RadioInstance radio) {
        return SESSIONS.containsKey(radio) ? AUDIBLE_RANGE : RadioInjector.RANGE_BLOCKS;
    }

    /** Radios that cannot be simulated here are left unbound and play as vanilla positional sound. */
    public static void bind(Minecraft mc, RadioInstance instance, RadioPcmFeed.Handle feed) {
        if (!AcousticAvailability.possible()) return;
        var session = new RadioSimulationSession(feed.format());
        feed.setProcessor(session);
        SESSIONS.put(instance, new Entry(session));
        // Panned until the next tick decides whether it is among the simulated radios.
        updateView(mc, instance, session, false);
        DimBlendRadio.LOGGER.info("[radio] Steam Audio simulation bound to {}", instance.pos());
    }

    public static void reset() {
        SESSIONS.values().forEach(entry -> entry.session.close());
        SESSIONS.clear();
        AcousticBakeScheduler.reset();
        snapshot = null;
        captured = 0;
        AcousticSnapshot.clearCache();
        AcousticSceneChanges.reset();
        level = null;
    }

    /**
     * @param playing radios playing their track
     * @param releasing stopped radios whose reverb is still ringing out: they keep the path they
     *        had (switching would cut the reverb) and take no slot from a playing radio
     */
    public static void tick(Minecraft mc, List<RadioInstance> playing, List<RadioInstance> releasing) {
        if (level != null && level != mc.level) reset();
        level = mc.level;
        SESSIONS.entrySet().removeIf(entry -> {
            if (playing.contains(entry.getKey()) || releasing.contains(entry.getKey())) return false;
            entry.getValue().session.close();
            return true;
        });
        var listener = mc.getSoundManager().getListenerTransform().position();
        Map<RadioInstance, Double> distances = new IdentityHashMap<>();
        Set<RadioInstance> previous = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var entry : SESSIONS.entrySet()) {
            if (!playing.contains(entry.getKey())) continue;
            distances.put(entry.getKey(), listener.distanceTo(SubLevelProjection.worldCenter(mc.level, entry.getKey().pos())));
            if (entry.getValue().selected) previous.add(entry.getKey());
        }
        Set<RadioInstance> selected = RadioSimulationSelection.select(distances, previous, AUDIBLE_RANGE);
        for (var entry : SESSIONS.entrySet()) {
            if (playing.contains(entry.getKey())) entry.getValue().selected = selected.contains(entry.getKey());
            // Otherwise only the post-mouse camera hook publishes poses and schedules ray jobs:
            // a client tick must not overwrite it with last frame's OpenAL listener transform.
            if (mc.isPaused()) updateView(mc, entry.getKey(), entry.getValue().session, false);
        }
        List<BlockPos> radios = new ArrayList<>(distances.size());
        for (RadioInstance radio : distances.keySet()) radios.add(radio.pos());
        AcousticBakeScheduler.tick(mc, radios, listener, System.nanoTime());
        for (var entry : SESSIONS.entrySet()) {
            var pathing = AcousticBakeScheduler.pathing(entry.getKey().pos());
            entry.getValue().session.setPathing(pathing == null ? null : pathing.bake(), pathing != null && pathing.stale());
        }
    }

    /** The diffracted path a radio's session currently renders, or null; for the bake view. */
    public static PathingField pathingField(BlockPos radio) {
        for (var entry : SESSIONS.entrySet()) {
            if (entry.getKey().pos().equals(radio)) return entry.getValue().session.pathingField();
        }
        return null;
    }

    /**
     * Per-frame view refresh: the tick loop owns selection, but head-turn and movement must reach
     * the audio pipeline at frame rate, not at 20 Hz. Every bound radio in range stays audible;
     * the selected ones are simulated against one snapshot covering all their mesh regions.
     */
    public static void frameRefresh(Minecraft mc, ListenerTransform listener) {
        if (mc.level == null || mc.isPaused()) return;
        long now = System.nanoTime();
        Vec3 position = listener.position();
        List<RadioInstance> simulated = new ArrayList<>();
        AABB required = null;
        for (var entry : SESSIONS.entrySet()) {
            RadioInstance radio = entry.getKey();
            Entry state = entry.getValue();
            var source = SubLevelProjection.worldCenter(mc.level, radio.pos());
            boolean inRange = position.distanceToSqr(source) < AUDIBLE_RANGE * AUDIBLE_RANGE;
            state.session.setView(source, position, listener.forward(), listener.up(), inRange, state.selected && inRange);
            if (state.selected && inRange && state.session.active()) {
                simulated.add(radio);
                AABB region = ReflectionMeshCache.region(position, source);
                required = required == null ? region : required.minmax(region);
            }
        }
        if (simulated.isEmpty()) return;
        if (AcousticSceneChanges.needsCapture(snapshot == null || !snapshot.covers(required), capturedRevision, captured, now)) {
            long revision = AcousticSceneChanges.revision();
            snapshot = AcousticSnapshot.capture(mc.level, required.inflate(CAPTURE_SLACK), simulated.get(0).pos());
            captured = now;
            // A mesh arriving during capture must still wake the following frame.
            capturedRevision = revision;
        }
        AcousticSnapshot poses = snapshot.currentPoses();
        for (RadioInstance radio : simulated) {
            SESSIONS.get(radio).session.simulate(poses.forEmitter(radio.pos()), now);
        }
    }

    private static void updateView(Minecraft mc, RadioInstance radio, RadioSimulationSession session, boolean simulated) {
        var listener = mc.getSoundManager().getListenerTransform();
        var source = SubLevelProjection.worldCenter(mc.level, radio.pos());
        boolean audible = !mc.isPaused() && listener.position().distanceToSqr(source) < AUDIBLE_RANGE * AUDIBLE_RANGE;
        session.setView(source, listener.position(), listener.forward(), listener.up(), audible, simulated && audible);
    }

    private RadioAcousticController() { }
}
