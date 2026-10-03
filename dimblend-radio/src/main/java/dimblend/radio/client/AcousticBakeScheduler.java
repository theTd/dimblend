package dimblend.radio.client;

import dev.ryanhcode.sable.companion.SableCompanion;
import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.AcousticMesh;
import dimblend.radio.acoustics.AcousticPathing;
import dimblend.radio.acoustics.AcousticRegionSignature;
import dimblend.radio.acoustics.AcousticSnapshot;
import dimblend.radio.acoustics.ReflectionGeometry;
import dimblend.radio.acoustics.bake.AcousticBakeFiles;
import dimblend.radio.acoustics.bake.PathingBake;
import dimblend.radio.acoustics.bake.PathingBaker;
import dimblend.radio.acoustics.bake.PathingProbePlacement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bakes each playing radio's pathing (sound bending round corners and through openings) once its
 * surroundings have stayed unchanged for a while and the client has capacity to spare
 * ({@link AcousticIdleGate}), keeps the bakes on disk ({@link AcousticBakeFiles}) and hands them
 * to the radios' sessions.
 * <p>
 * Per radio, once a second on the client thread: the region's {@link AcousticRegionSignature}
 * tells whether anything may have changed. A change makes the radio's bake stale at once (the
 * session then validates the baked routes); once the signature has held for
 * {@link #VERIFY_NANOS} a capture compares the region's sections with the bake's, which either
 * clears the doubt (a reloaded chunk, a door opened and closed again) or calls for a rebake after
 * {@link #STABLE_NANOS}. Captures happen here; placement, meshing, the bake and file access run on
 * one worker thread, whose results come back through {@link #tick}.
 */
public final class AcousticBakeScheduler {
    /**
     * Upper bound on a radio's probes. Bake time and data (about 5 bytes per probe pair, ~11 MiB
     * here) grow with its square; one floor level within 64 blocks is about 800 probes.
     */
    static final int MAX_PROBES = 1500;
    /** Unchanged this long, a region is compared with its bake. */
    static final long VERIFY_NANOS = 1_000_000_000L;
    /** Unchanged this long, a region is "nearly static" and worth baking. */
    static final long STABLE_NANOS = 5_000_000_000L;
    private static final long SAMPLE_NANOS = 1_000_000_000L;
    private static final long RETRY_NANOS = 10_000_000_000L;
    private static final int MAX_FAILURES = 3;
    /** Geometry kept beyond the probes: visibility rays leave the probe region a little. */
    private static final int MESH_MARGIN = 8;
    /**
     * Bake seconds per probe pair on one thread: twice the open-terrain spike (2304 probes in 3.0 s)
     * for denser visibility. More threads help less than linearly (1.6x on two, 2.5x on four).
     */
    static final double SECONDS_PER_PAIR = 1.0e-6;
    public static final long DISK_BUDGET = 512L << 20;
    /** Changes whenever a setting that shapes the bake does; older files are then ignored. */
    static final int SETTINGS = Objects.hash(AcousticPathing.VISIBILITY_SAMPLES, AcousticPathing.SAMPLE_RADIUS,
            AcousticPathing.VISIBILITY_THRESHOLD, AcousticPathing.VISIBILITY_RANGE, AcousticPathing.REGION_RADIUS,
            MAX_PROBES, RadioAcousticController.AUDIBLE_RANGE, 1);

    /** What a radio's session should use. */
    public record Pathing(PathingBake bake, boolean stale) { }

    /** Where a radio's bake stands, for the bake view. */
    public enum BakeState {
        /** Reading the radio's file. */
        LOADING,
        /** No bake, and the region not compared yet. */
        CHECKING,
        /** No bake yet: waiting for the region to settle and the client to be idle. */
        WAITING,
        BAKING,
        /** In use, and its region unchanged since it was baked. */
        VALID,
        /** In use with validation: its region changed, or has not been compared yet. */
        STALE,
        /** Nothing to bake: no walkable air around the radio. */
        NO_AIR,
        /** No bake, and baking it failed too often to try again. */
        FAILED
    }

    /**
     * A radio's bake as the bake view shows it; client thread.
     *
     * @param bake the bake in use, or null
     * @param changedSections sections ({@code SectionPos.asLong}) whose blocks differ from the bake's
     * @param verified the region was compared with the bake since its last change
     * @param needsBake a (re)bake is due once the region is stable and the client idle
     * @param stableIn seconds until the region counts as stable; 0 once it does
     * @param retryIn seconds until a failed or incomplete bake may be tried again; 0 if it may now
     * @param bakingFor seconds the running bake has taken so far; 0 when none runs
     * @param bakeThreads threads of the running or last bake
     * @param lastBakeSeconds how long the last bake took here; 0 for a bake read from disk
     * @param deferredProbes probes of a placement too large for the allowance it was tried with
     */
    public record Inspection(BlockPos radio, AABB region, BakeState state, PathingBake bake, long[] changedSections,
            boolean regionLoaded, boolean verified, boolean needsBake, double stableIn, double retryIn, double bakingFor,
            int bakeThreads, double lastBakeSeconds, int deferredProbes, int failures) { }

    private static final class RadioBake {
        final BlockPos radio;
        final AABB region;
        AcousticRegionSignature signature;
        long changedAt;
        PathingBake bake;
        /** The bake predates a change in its region, or may. */
        boolean stale = true;
        /** The current content was compared with the bake (or found to have none). */
        boolean verified;
        boolean needsBake;
        boolean loaded;
        /** A load or bake job is out. */
        boolean busy;
        /** A placement with this many probes did not fit the last allowance. */
        int deferredProbes;
        long retryAt;
        int failures;
        /** The last bake found no walkable air. */
        boolean noAir;
        /** Sections that differ from the bake's, as of {@link #compared}. */
        long[] changedSections = new long[0];
        /** The signature the sections were last compared at, or null. */
        AcousticRegionSignature compared;
        long bakeStartedAt;
        int bakeThreads;
        long lastBakeNanos;

        RadioBake(BlockPos radio) {
            this.radio = radio;
            region = new AABB(radio).inflate(AcousticPathing.REGION_RADIUS + MESH_MARGIN);
        }

        BakeState state() {
            if (busy) return loaded ? BakeState.BAKING : BakeState.LOADING;
            if (bake != null) return stale ? BakeState.STALE : BakeState.VALID;
            if (!loaded) return BakeState.LOADING;
            if (needsBake) return BakeState.WAITING;
            if (!verified) return BakeState.CHECKING;
            if (noAir) return BakeState.NO_AIR;
            return failures >= MAX_FAILURES ? BakeState.FAILED : BakeState.CHECKING;
        }

        void setBake(PathingBake next) {
            bake = next;
            changedSections = new long[0];
            compared = null;
        }
    }

    /** What a bake job came back with. */
    private sealed interface Outcome {
        record Baked(PathingBake bake, AcousticRegionSignature signature, long nanos) implements Outcome { }
        /** No walkable air around the radio: nothing for sound to bend round. */
        record Empty() implements Outcome { }
        record Incomplete() implements Outcome { }
        record Deferred(int probes) implements Outcome { }
        record Failed(Throwable error) implements Outcome { }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Radio acoustic bake");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final Map<BlockPos, RadioBake> RADIOS = new HashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> RESULTS = new ConcurrentLinkedQueue<>();
    private static final AcousticIdleGate GATE = new AcousticIdleGate();
    /** Worker-thread state: one native baker for the process. */
    private static PathingBaker baker;
    private static boolean bakerBroken;
    private static Level level;
    private static AcousticBakeFiles files;
    private static String world, dimension;
    /** Bumped on every reset; results from before it are dropped. */
    private static int generation;
    private static boolean baking;
    private static long lastSample;
    /** The bake view is open: compare a changed region with its bake every sample, not only once it settles. */
    private static boolean inspecting;

    /** The bake a radio's session should use now, or null. */
    public static Pathing pathing(BlockPos radio) {
        RadioBake state = RADIOS.get(radio);
        return state == null || state.bake == null ? null : new Pathing(state.bake, state.stale);
    }

    /** Client thread: while the bake view is open, edits show up within a second instead of once the region settles. */
    public static void setInspecting(boolean open) { inspecting = open; }

    /** Client thread: every tracked radio's bake, nearest the listener first. */
    public static List<Inspection> inspect(Vec3 listener, long now) {
        List<Inspection> inspections = new ArrayList<>();
        for (RadioBake state : RADIOS.values()) {
            double stableIn = state.signature == null ? STABLE_NANOS / 1e9 : Math.max(0, STABLE_NANOS - (now - state.changedAt)) / 1e9;
            boolean running = state.busy && state.loaded;
            inspections.add(new Inspection(state.radio, state.region, state.state(), state.bake, state.changedSections,
                    state.signature != null && state.signature.loaded(), state.verified, state.needsBake, stableIn,
                    Math.max(0, state.retryAt - now) / 1e9, running ? (now - state.bakeStartedAt) / 1e9 : 0,
                    state.bakeThreads, state.lastBakeNanos / 1e9, state.deferredProbes, state.failures));
        }
        inspections.sort(Comparator.comparingDouble(inspection -> Vec3.atCenterOf(inspection.radio()).distanceToSqr(listener)));
        return inspections;
    }

    /** Client thread: the idle gate's last decision. */
    public static AcousticIdleGate.Status gateStatus() { return GATE.status(); }

    /** Client thread: a bake is running. */
    public static boolean baking() { return baking; }

    /** Client thread, every tick. */
    public static void tick(Minecraft mc, Collection<BlockPos> radios, Vec3 listener, long now) {
        for (Runnable result; (result = RESULTS.poll()) != null; ) result.run();
        if (mc.level != level) {
            reset();
            level = mc.level;
            if (level != null) {
                files = new AcousticBakeFiles(mc.gameDirectory.toPath().resolve("dimblend-radio").resolve("acoustic-bakes"));
                world = worldName(mc);
                dimension = level.dimension().location().toString();
            }
        }
        var allowance = GATE.sample(mc, baking, now);
        if (level == null || now - lastSample < SAMPLE_NANOS) return;
        lastSample = now;
        RADIOS.values().removeIf(state -> !state.busy && !radios.contains(state.radio));
        List<RadioBake> candidates = new ArrayList<>();
        for (BlockPos radio : radios) {
            // Structures move: their radios keep the live direct path only.
            if (SableCompanion.INSTANCE.getContaining(level, radio.getX() >> 4, radio.getZ() >> 4) != null) continue;
            RadioBake state = RADIOS.computeIfAbsent(radio.immutable(), RadioBake::new);
            observe(state, now);
            if (state.needsBake && !state.busy && now >= state.retryAt && now - state.changedAt >= STABLE_NANOS
                    && state.signature.loaded()) {
                candidates.add(state);
            }
        }
        if (baking || !allowance.open() || candidates.isEmpty()) return;
        candidates.sort(Comparator.comparingDouble(state -> Vec3.atCenterOf(state.radio).distanceToSqr(listener)));
        for (RadioBake state : candidates) {
            if (state.deferredProbes > 0 && estimate(state.deferredProbes, allowance.threads()) > allowance.seconds()) continue;
            startBake(state, allowance, now);
            return;
        }
    }

    /** Estimated bake seconds for this many probes on this many threads. */
    static double estimate(int probes, int threads) {
        double speedup = Math.min(3, Math.pow(Math.max(1, threads), 0.66));
        return (double) probes * probes * SECONDS_PER_PAIR / speedup;
    }

    private static void observe(RadioBake state, long now) {
        var signature = AcousticRegionSignature.of(level, state.region);
        if (!signature.equals(state.signature)) {
            state.signature = signature;
            state.changedAt = now;
            state.verified = false;
            state.stale = true;
            state.deferredProbes = 0;
        }
        if (inspecting && state.bake != null && signature.loaded() && !signature.equals(state.compared)) {
            compare(state, false);
        }
        if (state.busy) return;
        if (!state.loaded) {
            load(state);
            return;
        }
        if (state.verified || !signature.loaded() || now - state.changedAt < VERIFY_NANOS) return;
        if (state.bake == null) {
            state.needsBake = true;
            state.verified = true;
            return;
        }
        compare(state, true);
    }

    /**
     * Captures the region and records which of its sections differ from the bake's. With
     * {@code verify}, a complete capture also settles whether the bake is still valid.
     */
    private static void compare(RadioBake state, boolean verify) {
        var capture = AcousticSnapshot.captureTerrain(level, state.region, state.radio);
        long[] keys = capture.sectionKeys(state.region);
        long[] sections = capture.terrainSections(keys);
        state.changedSections = changedSections(state.bake, keys, sections);
        state.compared = state.signature;
        if (!verify || !complete(sections)) return;
        boolean same = Arrays.equals(keys, state.bake.sectionKeys()) && Arrays.equals(sections, state.bake.sectionStates());
        state.stale = !same;
        state.needsBake = !same;
        state.verified = true;
    }

    /**
     * The sections whose captured state differs from the bake's; sections not captured now (an
     * unloaded chunk) are unknown, not changed.
     */
    static long[] changedSections(PathingBake bake, long[] keys, long[] sections) {
        Map<Long, Long> baked = new HashMap<>(bake.sectionKeys().length * 2);
        for (int i = 0; i < bake.sectionKeys().length; i++) baked.put(bake.sectionKeys()[i], bake.sectionStates()[i]);
        long[] changed = new long[keys.length];
        int count = 0;
        for (int i = 0; i < keys.length; i++) {
            if (sections[i] == ReflectionGeometry.UNLOADED || sections[i] == ReflectionGeometry.UNCAPTURED) continue;
            Long before = baked.get(keys[i]);
            if (before == null || before != sections[i]) changed[count++] = keys[i];
        }
        return Arrays.copyOf(changed, count);
    }

    private static boolean complete(long[] sections) {
        for (long section : sections) {
            if (section == ReflectionGeometry.UNLOADED || section == ReflectionGeometry.UNCAPTURED) return false;
        }
        return true;
    }

    private static void load(RadioBake state) {
        state.busy = true;
        int expected = generation;
        Path file = files.pathingFile(world, dimension, state.radio);
        var store = files;
        WORKER.execute(() -> {
            PathingBake bake = null;
            try {
                bake = store.read(file, state.radio, SETTINGS);
            } catch (IOException | RuntimeException error) {
                DimBlendRadio.LOGGER.warn("[radio] could not read baked pathing {}", file, error);
            }
            PathingBake loaded = bake;
            RESULTS.add(() -> {
                if (expected != generation) return;
                state.busy = false;
                state.loaded = true;
                if (loaded != null && state.bake == null) {
                    state.setBake(loaded);
                    state.stale = true;
                    state.verified = false;
                    DimBlendRadio.LOGGER.info("[radio] loaded baked pathing for {}: {} probes", state.radio, loaded.probeCount());
                }
            });
        });
    }

    private static void startBake(RadioBake state, AcousticIdleGate.Allowance allowance, long now) {
        var capture = AcousticSnapshot.captureTerrain(level, state.region, state.radio);
        long[] keys = capture.sectionKeys(state.region);
        long[] sections = capture.terrainSections(keys);
        if (!complete(sections)) {
            state.retryAt = now + RETRY_NANOS;
            return;
        }
        state.busy = true;
        state.bakeStartedAt = now;
        state.bakeThreads = allowance.threads();
        baking = true;
        int expected = generation;
        var signature = state.signature;
        BlockPos radio = state.radio;
        AABB region = state.region;
        Path file = files.pathingFile(world, dimension, radio);
        var store = files;
        WORKER.execute(() -> {
            Outcome outcome;
            try {
                outcome = bake(capture, radio, region, keys, sections, allowance, signature, store, file);
            } catch (RuntimeException | Error error) {
                outcome = new Outcome.Failed(error);
            }
            Outcome result = outcome;
            RESULTS.add(() -> {
                baking = false;
                if (expected == generation) finish(state, result, System.nanoTime());
            });
        });
    }

    /** Worker thread. */
    private static Outcome bake(AcousticSnapshot capture, BlockPos radio, AABB region, long[] keys, long[] sections,
            AcousticIdleGate.Allowance allowance, AcousticRegionSignature signature, AcousticBakeFiles store, Path file) {
        long start = System.nanoTime();
        var probes = PathingProbePlacement.place((x, y, z) -> switch (capture.terrainOpenness(x, y, z)) {
            case OPEN -> PathingProbePlacement.OPEN;
            case SOLID -> PathingProbePlacement.SOLID;
            case UNKNOWN -> PathingProbePlacement.UNKNOWN;
        }, radio, AcousticPathing.REGION_RADIUS, MAX_PROBES);
        if (!probes.complete()) return new Outcome.Incomplete();
        if (probes.count() == 0) return new Outcome.Empty();
        if (estimate(probes.count(), allowance.threads()) > allowance.seconds()) return new Outcome.Deferred(probes.count());
        if (bakerBroken) throw new IllegalStateException("Pathing baker unavailable");
        if (baker == null) {
            try {
                baker = new PathingBaker();
            } catch (RuntimeException | Error error) {
                bakerBroken = true;
                throw error;
            }
        }
        long placed = System.nanoTime();
        var mesh = capture.terrainMesh(region, Vec3.atLowerCornerOf(radio), new AcousticMesh.Workspace());
        long meshed = System.nanoTime();
        byte[] batch = baker.bake(mesh, probes, (float) RadioAcousticController.AUDIBLE_RANGE, allowance.threads(), fraction -> { });
        long baked = System.nanoTime();
        // Kept in the radio's frame, like the batch: the bake view draws them.
        Vec3 origin = Vec3.atLowerCornerOf(radio);
        double[] centres = probes.centres();
        float[] local = new float[centres.length];
        for (int i = 0; i < centres.length; i += 3) {
            local[i] = (float) (centres[i] - origin.x);
            local[i + 1] = (float) (centres[i + 1] - origin.y);
            local[i + 2] = (float) (centres[i + 2] - origin.z);
        }
        var bake = new PathingBake(radio, keys, sections, local, probes.cellSize(), batch);
        DimBlendRadio.LOGGER.info("[radio] baked pathing for {}: {} probes ({}-block cells), {} triangles, {} KiB;"
                + " placement {} ms, mesh {} ms, bake {} ms on {} threads", radio, probes.count(), probes.cellSize(),
                mesh.triangleCount(), batch.length / 1024, (placed - start) / 1_000_000, (meshed - placed) / 1_000_000,
                (baked - meshed) / 1_000_000, allowance.threads());
        try {
            store.write(file, bake, SETTINGS);
            store.trim(DISK_BUDGET);
        } catch (IOException | RuntimeException error) {
            DimBlendRadio.LOGGER.warn("[radio] could not save baked pathing {}", file, error);
        }
        return new Outcome.Baked(bake, signature, baked - meshed);
    }

    /** Client thread. */
    private static void finish(RadioBake state, Outcome outcome, long now) {
        state.busy = false;
        switch (outcome) {
            case Outcome.Baked baked -> {
                state.setBake(baked.bake());
                state.lastBakeNanos = baked.nanos();
                state.needsBake = false;
                state.noAir = false;
                state.deferredProbes = 0;
                state.failures = 0;
                // Edited while baking: still the best there is, but re-verified before trusted.
                state.stale = !baked.signature().equals(state.signature);
                if (state.stale) state.verified = false;
            }
            case Outcome.Empty empty -> {
                state.setBake(null);
                state.needsBake = false;
                state.noAir = true;
            }
            case Outcome.Incomplete incomplete -> state.retryAt = now + RETRY_NANOS;
            case Outcome.Deferred deferred -> state.deferredProbes = deferred.probes();
            case Outcome.Failed failed -> {
                state.retryAt = now + 6 * RETRY_NANOS;
                if (++state.failures >= MAX_FAILURES) state.needsBake = false;
                DimBlendRadio.LOGGER.warn("[radio] pathing bake failed for {} ({} of {})", state.radio, state.failures,
                        MAX_FAILURES, failed.error());
            }
        }
    }

    /** The singleplayer save folder or the server address. */
    private static String worldName(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server != null) {
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "local-" + (root.getFileName() == null ? "world" : root.getFileName().toString());
        }
        var data = mc.getCurrentServer();
        return data != null ? "server-" + data.ip : "unknown";
    }

    /** Client thread: drops every radio's state; jobs still out finish and are ignored. */
    public static void reset() {
        generation++;
        RADIOS.clear();
        level = null;
        files = null;
    }

    private AcousticBakeScheduler() { }
}
