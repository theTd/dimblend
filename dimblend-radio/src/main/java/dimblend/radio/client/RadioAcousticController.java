package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.SubLevelProjection;
import dimblend.radio.acoustics.AcousticSnapshot;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

public final class RadioAcousticController {
    /**
     * Acoustic audibility radius: the simulated path carries a linear-to-zero gain curve out to
     * this distance (the vanilla fallback path keeps RANGE_BLOCKS=64). The snapshot capture
     * radius adds the 24-block mesh margin around the source/listener bounds.
     */
    public static final double AUDIBLE_RANGE = 96;

    private static final class Entry {
        final RadioSimulationSession session;
        AcousticSnapshot snapshot;
        long captured;
        boolean selected;
        Entry(RadioSimulationSession session) { this.session = session; }
    }
    private static final Map<RadioInstance, Entry> SESSIONS = new IdentityHashMap<>();
    private static Level level;

    public static void bind(Minecraft mc, RadioInstance instance, RadioPcmFeed.Handle feed) {
        if ("false".equalsIgnoreCase(System.getProperty("dimblend.radio.reverb"))
                || !System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64")
                || ModList.get().isLoaded("sound_physics_remastered")) return;
        var session = new RadioSimulationSession(feed.format());
        feed.setProcessor(session);
        SESSIONS.put(instance, new Entry(session));
        updateView(mc, instance, session);
        DimBlendRadio.LOGGER.info("[radio] Steam Audio simulation bound to {}", instance.pos());
    }

    public static void reset() {
        SESSIONS.values().forEach(entry -> entry.session.close());
        SESSIONS.clear();
        level = null;
    }

    public static void tick(Minecraft mc, List<RadioInstance> radios) {
        if (level != null && level != mc.level) reset();
        level = mc.level;
        SESSIONS.entrySet().removeIf(entry -> {
            if (radios.contains(entry.getKey())) return false;
            entry.getValue().session.close();
            return true;
        });
        boolean capturedThisTick = false;
        long now = System.nanoTime();
        var listener = mc.getSoundManager().getListenerTransform().position();
        var selected = new ArrayList<>(radios);
        selected.sort(Comparator.comparingDouble(radio -> listener.distanceToSqr(SubLevelProjection.worldCenter(mc.level, radio.pos()))));
        if (selected.size() > 4) selected.subList(4, selected.size()).clear();
        for (var entry : SESSIONS.entrySet()) {
            RadioInstance radio = entry.getKey();
            Entry state = entry.getValue();
            state.selected = selected.contains(radio);
            updateView(mc, radio, state.session, state.selected);
            if (mc.isPaused() || !selected.contains(radio)
                    || listener.distanceToSqr(SubLevelProjection.worldCenter(mc.level, radio.pos())) >= AUDIBLE_RANGE * AUDIBLE_RANGE) continue;
            if (!capturedThisTick && now - state.captured >= 500_000_000L) {
                state.snapshot = AcousticSnapshot.capture(mc.level, mc.getSoundManager().getListenerTransform().position(), radio.pos(), AUDIBLE_RANGE + 24);
                state.captured = now;
                capturedThisTick = true;
            }
            if (state.snapshot != null) state.session.simulate(state.snapshot.currentPoses(), now);
        }
    }

    /**
     * Per-frame view refresh: the tick loop owns snapshots and simulation cadence, but head-turn
     * and movement must reach the audio pipeline at frame rate, not at 20 Hz.
     */
    public static void frameRefresh(Minecraft mc) {
        if (mc.level == null || mc.isPaused()) return;
        var listener = mc.getSoundManager().getListenerTransform();
        for (var entry : SESSIONS.entrySet()) {
            RadioInstance radio = entry.getKey();
            Entry state = entry.getValue();
            var source = SubLevelProjection.worldCenter(mc.level, radio.pos());
            state.session.setView(source, listener.position(), listener.forward(), listener.up(),
                    state.selected && listener.position().distanceToSqr(source) < AUDIBLE_RANGE * AUDIBLE_RANGE);
        }
    }

    private static void updateView(Minecraft mc, RadioInstance radio, RadioSimulationSession session) {
        updateView(mc, radio, session, true);
    }

    private static void updateView(Minecraft mc, RadioInstance radio, RadioSimulationSession session, boolean selected) {
        var listener = mc.getSoundManager().getListenerTransform();
        var source = SubLevelProjection.worldCenter(mc.level, radio.pos());
        session.setView(source, listener.position(), listener.forward(), listener.up(),
                selected && !mc.isPaused() && listener.position().distanceToSqr(source) < AUDIBLE_RANGE * AUDIBLE_RANGE);
    }

    private RadioAcousticController() { }
}
