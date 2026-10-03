package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.AcousticAvailability;
import dimblend.radio.acoustics.AcousticUpdateGate;
import dimblend.radio.acoustics.ReflectionGeometry;
import dimblend.radio.acoustics.ReflectionMeshCache;
import dimblend.radio.acoustics.PhononNotReadyException;
import dimblend.radio.acoustics.SteamAudio;
import dimblend.radio.acoustics.SteamSimulation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.phys.Vec3;

/**
 * GPU reflections of every simulated radio at once. Radios whose streams share a sampling rate
 * share one Steam Audio simulator: one Radeon Rays scene around the listener and all of them,
 * meshed and uploaded once, and one listener-centric trace per run in which each radio's own
 * sources gather its own responses (its IRs). Every native call happens on {@link #WORKER}.
 */
final class SharedReflectionSimulator {
    static final ExecutorService WORKER = RadioSimulationSession.worker("Radio acoustic reflections");
    /** Motion updates are scheduled from the latest camera pose, independently of client ticks. */
    private static final long INTERVAL = 50_000_000L;
    private static final int BOUNCES = 128;
    /**
     * Responses each radio averages (its renderer's echo slots): the radio has a source per slot,
     * and its runs take turns among them, so it hears the mean of its last few runs. One run's
     * response is a Monte Carlo estimate from {@link SteamSimulation#GPU_RAYS} listener rays; where
     * few of them reach the radio (a closed train car far below the listener) it swings by a few dB
     * from run to run and now and then drops by 20 dB, heard as flutter and dropouts in the echo.
     * Steam Audio's own averaging restarts whenever the source, the listener or the scene moved,
     * which on a moving train is every run.
     */
    static final int ECHO_SLOTS = 3;

    /** One simulator, the scene its radios share and who they are. */
    static final class Engine {
        final int rate;
        final SteamSimulation simulation;
        /** Worker. */
        final ReflectionMeshCache meshes = new ReflectionMeshCache();
        /** Worker: the radios with a source in {@link #simulation}. */
        final List<RadioSimulationSession> members = new ArrayList<>();
        /** Worker: a run failed natively; radios joining later get a new engine. */
        boolean broken;
        /** A run is queued or running. */
        final AtomicBoolean busy = new AtomicBoolean();
        /** Client thread: when the last run was scheduled. */
        long lastRun;

        private Engine(int rate, SteamSimulation simulation) {
            this.rate = rate;
            this.simulation = simulation;
        }
    }

    /** A radio's part of an engine: a source per echo slot, whose outputs (and IRs) are that radio's alone. */
    static final class Membership {
        private final Engine engine;
        private final List<SteamSimulation.Source> sources;
        /** Worker: the slot the next run that takes turns simulates. */
        private int next;

        private Membership(Engine engine, List<SteamSimulation.Source> sources) {
            this.engine = engine;
            this.sources = sources;
        }

        SteamSimulation simulation() { return engine.simulation; }

        /** In slot order. */
        List<SteamSimulation.Source> sources() { return sources; }

        /** Worker: the slot whose turn it is; the following run takes the next one. */
        private int takeTurn() {
            int slot = next;
            next = (next + 1) % sources.size();
            return slot;
        }
    }

    /** The radios' shared scene as of one frame, and the scene revision it was captured at. */
    private record Scene(ReflectionGeometry geometry, long revision) { }

    /** Worker: the engine new radios of each sampling rate join. */
    private static final Map<Integer, Engine> ENGINES = new HashMap<>();
    private static volatile Scene latestScene;

    /**
     * Worker: adds {@code session}'s sources to the engine for {@code rate}, creating the engine
     * first if there is none (or only a broken one).
     */
    static Membership join(RadioSimulationSession session, int rate) {
        Engine engine = ENGINES.get(rate);
        if (engine == null || engine.broken) {
            SteamSimulation simulation;
            try {
                // As many radios as are selected at once share a trace, even when each fills all its
                // slots; releasing ones beyond them take another.
                simulation = new SteamSimulation(rate, SteamSimulation.REFLECTIONS, true,
                        RadioSimulationSelection.LIMIT * ECHO_SLOTS);
            } catch (RuntimeException | Error error) {
                // A download in progress is retried, not a broken machine: leave AcousticAvailability alone.
                if (!(error instanceof PhononNotReadyException)) AcousticAvailability.gpuUnavailable(error);
                throw error;
            }
            AcousticAvailability.gpuAvailable();
            engine = new Engine(rate, simulation);
            ENGINES.put(rate, engine);
        }
        List<SteamSimulation.Source> sources = new ArrayList<>(ECHO_SLOTS);
        try {
            for (int slot = 0; slot < ECHO_SLOTS; slot++) sources.add(engine.simulation.addSource());
        } catch (RuntimeException | Error error) {
            for (SteamSimulation.Source added : sources) added.close();
            if (engine.members.isEmpty()) close(engine);
            throw error;
        }
        engine.members.add(session);
        return new Membership(engine, List.copyOf(sources));
    }

    /**
     * Worker: removes {@code session}'s sources once its renderer no longer reads their IRs; the
     * last radio to leave an engine closes it.
     */
    static void leave(RadioSimulationSession session, Membership membership) {
        Engine engine = membership.engine;
        engine.members.remove(session);
        try {
            for (SteamSimulation.Source source : membership.sources) source.close();
        } finally {
            if (engine.members.isEmpty()) close(engine);
        }
    }

    private static void close(Engine engine) {
        ENGINES.remove(engine.rate, engine);
        engine.simulation.close();
    }

    /**
     * Client thread, once a frame: schedules a run per engine when one of its radios is due. Content
     * changes jump the motion cadence; motion alone (the listener's, a radio's or a structure's)
     * waits for it.
     *
     * @param sessions the radios simulated this frame
     * @param scene their shared scene, every one of them open in it
     *     ({@link dimblend.radio.acoustics.AcousticSnapshot#forEmitters}); also the update gate's key
     * @param revision the scene revision it was captured at
     */
    static void simulate(List<RadioSimulationSession> sessions, ReflectionGeometry scene, long revision, long now) {
        latestScene = new Scene(scene, revision);
        Map<Engine, List<RadioSimulationSession>> byEngine = new LinkedHashMap<>();
        for (RadioSimulationSession session : sessions) {
            Membership membership = session.reflections();
            if (membership != null && session.reflectionView() != null) {
                byEngine.computeIfAbsent(membership.engine, key -> new ArrayList<>()).add(session);
            }
        }
        for (var entry : byEngine.entrySet()) {
            Engine engine = entry.getKey();
            boolean changed = false;
            for (RadioSimulationSession member : entry.getValue()) changed |= AcousticUpdateGate.geometryChanged(member, scene, true);
            if (!(now - engine.lastRun >= INTERVAL || changed) || !engine.busy.compareAndSet(false, true)) continue;
            engine.lastRun = now;
            List<RadioSimulationSession> due = new ArrayList<>();
            for (RadioSimulationSession member : entry.getValue()) {
                RadioSimulationSession.View view = member.reflectionView();
                if (view != null && AcousticUpdateGate.shouldSimulate(member, scene, view.source(), view.listener(), true)) due.add(member);
            }
            if (due.isEmpty()) engine.busy.set(false);
            else WORKER.execute(() -> run(engine, due));
        }
    }

    /**
     * Worker: one run for the radios that are due, and any other member that moved meanwhile. The
     * mesh region spans every simulated member, due or not, so it does not change with who runs.
     * Each runner simulates the source of the slot whose turn it is, or every slot while it lacks
     * a response in any (it just joined, or its renderer was reset).
     */
    private static void run(Engine engine, List<RadioSimulationSession> due) {
        long start = System.nanoTime();
        List<RadioSimulationSession> wanted = new ArrayList<>();
        try {
            if (engine.broken) return;
            Scene scene = latestScene;
            // One entry per simulated source: its radio and the radio's slot.
            List<RadioSimulationSession> runners = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            List<SteamSimulation.Source> sources = new ArrayList<>();
            List<Vec3> positions = new ArrayList<>(), region = new ArrayList<>();
            Vec3 listener = null;
            for (RadioSimulationSession member : engine.members) {
                RadioSimulationSession.View view = member.reflectionView();
                Membership membership = member.reflections();
                if (view == null || membership == null) continue;
                wanted.add(member);
                // Every radio hears the same camera.
                if (listener == null) listener = view.listener();
                region.add(view.source());
                // Records this pose as simulated for the due ones too.
                boolean moved = AcousticUpdateGate.shouldSimulate(member, scene.geometry, view.source(), view.listener(), true);
                if (moved || due.contains(member)) {
                    boolean refill = !member.echoFilled();
                    int first = refill ? 0 : membership.takeTurn();
                    int end = refill ? membership.sources.size() : first + 1;
                    for (int slot = first; slot < end; slot++) {
                        runners.add(member);
                        slots.add(slot);
                        sources.add(membership.sources.get(slot));
                        positions.add(view.source());
                    }
                }
            }
            if (runners.isEmpty()) return;
            var geometry = engine.meshes.get(scene.geometry, listener, region);
            long uploads = engine.simulation.uploads();
            List<SteamAudio.SimulationOutputs> outputs;
            try {
                // Engines are long-lived: re-run in place; simulateGpu re-uploads the scene mesh in
                // place when the geometry changed. Replacing the engine per run was found to NaN the
                // wet field after the second close-retain cycle and stall the worker on native teardown.
                outputs = engine.simulation.simulateGpu(geometry.terrain(), geometry.structures(), listener,
                        sources, positions, BOUNCES);
            } catch (RuntimeException | Error error) {
                engine.broken = true;
                for (RadioSimulationSession member : List.copyOf(engine.members)) member.fail(error);
                return;
            }
            AcousticRecording recording = AcousticRecording.active();
            if (recording != null) {
                // radio:slot per simulated source
                StringBuilder radios = new StringBuilder();
                for (int i = 0; i < runners.size(); i++) {
                    radios.append(radios.isEmpty() ? "" : "+").append(runners.get(i).track(recording).number())
                            .append(':').append(slots.get(i));
                }
                recording.event(null, "reflection_run", String.format(Locale.ROOT,
                        "ms=%.1f radios=%s of %d triangles=%d uploaded=%s rate=%d scene_revision=%d",
                        (System.nanoTime() - start) / 1e6, radios, wanted.size(), geometry.triangleCount(),
                        engine.simulation.uploads() != uploads, engine.rate, scene.revision));
            }
            for (int i = 0; i < runners.size(); i++) {
                runners.get(i).publishReflections(slots.get(i), outputs.get(i), scene.revision, start);
            }
            DimBlendRadio.LOGGER.debug("[radio] GPU acoustic IR in {} ms ({} tris, {} sources, {} radios)",
                    (System.nanoTime() - start) / 1_000_000, geometry.triangleCount(), runners.size(), wanted.size());
        } catch (RuntimeException | Error error) {
            // Not the native run (meshing, scheduling): every radio of the run would meet it again.
            for (RadioSimulationSession member : wanted) member.fail(error);
        } finally {
            engine.busy.set(false);
        }
    }

    private SharedReflectionSimulator() { }
}
