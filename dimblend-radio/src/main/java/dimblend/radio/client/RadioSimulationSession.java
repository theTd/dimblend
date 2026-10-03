package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.AcousticPathing;
import dimblend.radio.acoustics.AcousticSnapshot;
import dimblend.radio.acoustics.AcousticTuningProperty;
import dimblend.radio.acoustics.AcousticUpdateGate;
import dimblend.radio.acoustics.PathingField;
import dimblend.radio.acoustics.SteamAudio;
import dimblend.radio.acoustics.SteamRenderer;
import dimblend.radio.acoustics.SteamSimulation;
import dimblend.radio.acoustics.bake.PathingBake;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;
import net.minecraft.world.phys.Vec3;

/**
 * One radio's acoustic pipeline. Audio blocks go through two stages: the position-dependent one
 * (propagation delay, occlusion, reflection convolution, path selection) runs on a DSP thread
 * {@link #LOOKAHEAD_BLOCKS} blocks ahead of playback, under this monitor; the orientation-dependent
 * one (HRTF/panning, decode, crossfades) runs on the sound thread as each block is played, so head
 * turns keep the vanilla buffer latency while the convolution leaves the sound thread.
 * <p>
 * Direct sound and pathing are simulated per radio; reflections by the {@link SharedReflectionSimulator},
 * in which this radio has its own source.
 */
public final class RadioSimulationSession implements RadioPcmProcessor {
    private static final ExecutorService DIRECT = worker("Radio acoustic direct");
    private static final ExecutorService DSP = Executors.newFixedThreadPool(2, daemon("Radio acoustic DSP"));
    /** Blocks the DSP thread works ahead of playback; also the stream's start delay (~23 ms at 44.1 kHz). */
    static final int LOOKAHEAD_BLOCKS = 2;
    private static final AcousticTuningProperty WET_GAIN =
            new AcousticTuningProperty("dimblend.radio.acoustic.wetgain", 3, Float.MAX_VALUE);
    /** Pathing cadence (1 to 3.5 ms a run); slower while a stale bake's routes are re-traced. */
    private static final long PATHING_INTERVAL = 50_000_000L, STALE_PATHING_INTERVAL = 200_000_000L;
    /**
     * @param audible the radio is heard at all (in range, game not paused)
     * @param simulated Steam Audio renders it; other audible radios are stereo-panned
     */
    record View(Vec3 source, Vec3 listener, Vec3 ahead, Vec3 up, boolean audible, boolean simulated) { }
    /** How an audio block is produced. */
    private enum Path { SILENT, PANNED, RENDERED }
    /** @param tail no input arrived for this block (the stream ended): the delay line and reverb drain */
    private record Pending(float[] input, boolean tail) { }
    /**
     * A block after the position-dependent stage.
     * @param rendered the renderer's prepared block, when the rendered path is involved
     * @param panned the panner's mono input, when the panned path is involved
     * @param resetPanner the panned path starts out of silence
     * @param staging what the rendered part was prepared with, while a recording runs; else null
     */
    private record Staged(Path previous, Path next, SteamRenderer renderer, SteamRenderer.Prepared rendered,
            float[] panned, boolean resetPanner, AcousticRecording.Staging staging) {
        static final Staged SILENT = new Staged(Path.SILENT, Path.SILENT, null, null, null, false, null);

        /** How the block was produced, as a recording names it. */
        String mode() {
            String to = next.name().toLowerCase(Locale.ROOT);
            return previous == next ? to : previous.name().toLowerCase(Locale.ROOT) + ">" + to;
        }
    }
    private final AudioFormat format;
    private final int rate;
    /** Names the radio in recordings. */
    private final String label;
    private final AtomicBoolean directBusy = new AtomicBoolean();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile AcousticSnapshot latestSnapshot;
    private volatile SteamSimulation directEngine;
    /** This radio's source in the shared reflection simulator; set with {@link #renderer}. */
    private volatile SharedReflectionSimulator.Membership reflections;
    // JNA embedded sub-structures share the owning SimulationOutputs' native backing store.
    // Pin the owner: if it is collected, the params structs become garbage silently
    // (wet dies, dry params jump around). Consumers pin the holder until the native call returns.
    private volatile SteamAudio.SimulationOutputs directOutputs, reflectionOutputs;
    private volatile long directRevision = -1, reflectionRevision = -1;
    /** When the direct outputs and the IR were last published, and how many IRs were; for recordings. */
    private volatile long directPublishedAt, reflectionPublishedAt, reflectionsPublished;
    private volatile View view = new View(Vec3.ZERO, Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(0, 1, 0), false, false);
    /** The radio's baked pathing as last handed over (client thread), or null. */
    private volatile AcousticBakeScheduler.Pathing pathing;
    /** A pathing run is owed (new bake, or skipped by the cadence) and may run from this time on. */
    private volatile boolean pathingOwed;
    private volatile long pathingDueAt;
    /** The diffracted path for the renderer, shaped on the direct worker; null when there is none. */
    private volatile PathingField pathingField;
    /** Direct-worker state: the bake attached to the direct engine and when pathing last ran. */
    private PathingBake attachedBake;
    private long lastPathing;
    private volatile boolean closed;
    private volatile boolean failed;
    private SteamRenderer renderer;
    private long lastDirect;
    private int invalidFields;
    private final int lookahead;
    /** Submitted blocks in order; the staged ones are prepared, the pending ones not yet. */
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Staged> staged = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean dspScheduled = new AtomicBoolean();
    // Sound-thread state, guarded by the audio lock (process/hasTail). The sound thread takes this
    // monitor only when the DSP thread fell behind and a block must be staged inline.
    private final Object audio = new Object();
    private final RadioStereoPanner panner = new RadioStereoPanner();
    /** Blocks submitted and not yet played. */
    private int queued;
    private boolean inputEnded;
    // Position-stage state, guarded by this monitor.
    /** Path of the previous block; {@code null} before the first. */
    private Path path;
    /** The panner is playing undelayed input (no usable renderer when it took over). */
    private boolean pannedRaw;
    /** Input samples still inside the renderer's propagation delay after the input ended. */
    private int directTail;
    /** What the last block went through the renderer with, when it was prepared for a recording. */
    private AcousticRecording.Staging preparedStaging;
    /** Sound-thread state: the block last taken was staged inline, the DSP thread having fallen behind. */
    private boolean takenInline;
    /** As of the latest staged block: delayed direct sound or reverb is still sounding. */
    private volatile boolean tailLive;
    /** Reflection-worker state. */
    private boolean firstIrLogged;
    private final boolean gpuEnabled = !"false".equalsIgnoreCase(System.getProperty("dimblend.radio.acoustic.gpu"));

    public RadioSimulationSession(AudioFormat input) {
        this(input, "radio");
    }

    /** @param label names the radio in recordings */
    public RadioSimulationSession(AudioFormat input, String label) {
        this(input, label, LOOKAHEAD_BLOCKS);
    }

    /** @param lookahead blocks prepared ahead of playback; 0 stages every block inline, in its own process call */
    RadioSimulationSession(AudioFormat input, int lookahead) {
        this(input, "radio", lookahead);
    }

    private RadioSimulationSession(AudioFormat input, String label, int lookahead) {
        rate = Math.round(input.getSampleRate());
        format = new AudioFormat(rate, 16, 2, true, false);
        this.label = label;
        this.lookahead = lookahead;
    }

    String label() { return label; }

    /** This radio's track in {@code recording}. */
    AcousticRecording.Track track(AcousticRecording recording) {
        return recording.track(this, label, rate);
    }

    /** This radio's track in the running recording, or null while nothing is recorded. */
    private AcousticRecording.Track recordingTrack() {
        AcousticRecording recording = AcousticRecording.active();
        return recording == null ? null : track(recording);
    }

    /** Adds an event about this radio to the running recording, if one runs. */
    void record(String kind, Supplier<String> detail) {
        AcousticRecording.Track track = recordingTrack();
        if (track != null) track.event(kind, detail.get());
    }

    static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(daemon(name));
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Reflection worker: a source in the shared reflection simulator, then the direct engine. */
    private void joinReflections() {
        if (closed || failed) return;
        try {
            if (!gpuEnabled) throw new IllegalStateException("GPU acoustics disabled");
            installReflections(SharedReflectionSimulator.join(this, rate));
            // Direct CPU rays are part of the GPU acoustic session, never a fallback.
            if (!closed && !failed) DIRECT.execute(this::initializeDirect);
        } catch (RuntimeException | Error error) { fail(error); }
    }

    private void initializeDirect() {
        if (closed || failed) return;
        try {
            var engine = new SteamSimulation(rate, SteamSimulation.DIRECT | SteamSimulation.PATHING, false);
            if (closed || failed) engine.close();
            else directEngine = engine;
        } catch (RuntimeException | Error error) { fail(error); }
    }

    // Only the reflection worker constructs/destroys native resources. Publication and removal
    // share the position stage's monitor, so no block can be prepared with a retired IR pointer;
    // blocks already prepared get null from a closed renderer's spatial stage.
    private void installReflections(SharedReflectionSimulator.Membership membership) {
        SteamRenderer prepared;
        try { prepared = new SteamRenderer(membership.simulation().context(), rate, this::reflectionReset); }
        catch (RuntimeException | Error error) { SharedReflectionSimulator.leave(this, membership); throw error; }
        synchronized (this) {
            if (!closed && !failed) {
                renderer = prepared;
                reflectionOutputs = null;
                reflections = membership;
                return;
            }
        }
        prepared.close();
        SharedReflectionSimulator.leave(this, membership);
    }

    /** Reflection worker: the renderer goes first, so the source's IR is unused when the source leaves. */
    private void retireReflections() {
        SteamRenderer oldRenderer;
        SharedReflectionSimulator.Membership old;
        synchronized (this) {
            oldRenderer = renderer;
            old = reflections;
            renderer = null;
            reflections = null;
            reflectionOutputs = null;
        }
        try { if (oldRenderer != null) oldRenderer.close(); }
        finally { if (old != null) SharedReflectionSimulator.leave(this, old); }
    }

    /** This radio's source in the shared reflection simulator, or null before it joined or after it left. */
    SharedReflectionSimulator.Membership reflections() { return reflections; }

    /** Where the radio and listener are now, or null while this radio takes no part in reflection runs. */
    View reflectionView() {
        View current = view;
        return closed || failed || !current.simulated || reflections == null ? null : current;
    }

    /** Reflection worker: a shared run's outputs for this radio's source. */
    void publishReflections(SteamAudio.SimulationOutputs outputs, long revision, long started) {
        long count;
        synchronized (this) {
            if (closed || failed || renderer == null) return;
            reflectionOutputs = outputs;
            reflectionRevision = revision;
            reflectionPublishedAt = System.nanoTime();
            count = ++reflectionsPublished;
            renderer.reflectionsReady();
        }
        record("ir_ready", () -> String.format(Locale.ROOT, "ir_count=%d run_ms=%.1f scene_revision=%d",
                count, (System.nanoTime() - started) / 1e6, revision));
        if (!firstIrLogged) {
            firstIrLogged = true;
            DimBlendRadio.LOGGER.info("[radio] GPU convolution IR ready: {} channels, {} samples, {} ms",
                    outputs.reflections.channels, outputs.reflections.irSize, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private synchronized void reflectionReset() {
        reflectionOutputs = null;
        record("ir_invalid", () -> "non-finite reflections; effect reset, fresh simulation requested");
        AcousticUpdateGate.invalidateReflections(this);
        if (++invalidFields >= 3) {
            fail(new IllegalStateException("Repeated invalid GPU reflection output"));
        }
    }

    synchronized void fail(Throwable error) {
        if (failed) return;
        failed = true;
        directOutputs = null;
        reflectionOutputs = null;
        pathingField = null;
        AcousticUpdateGate.forget(this);
        record("failed", error::toString);
        DimBlendRadio.LOGGER.warn("[radio] acoustics disabled for this radio; using stereo panning (no CPU fallback)", error);
        DIRECT.execute(() -> { if (directEngine != null) { directEngine.close(); directEngine = null; } });
        SharedReflectionSimulator.WORKER.execute(this::retireReflections);
    }

    public boolean active() { return !closed && !failed; }

    /** Audible radios are simulated, as when this session is the only radio. */
    public void setView(Vec3 source, Vec3 listener, Vec3 ahead, Vec3 up, boolean audible) {
        setView(source, listener, ahead, up, audible, audible);
    }

    public void setView(Vec3 source, Vec3 listener, Vec3 ahead, Vec3 up, boolean audible, boolean simulated) {
        if (closed) return;
        view = new View(source, listener, ahead, up, audible, audible && simulated);
        // Native state is created once a radio is first simulated, never for panned-only radios.
        if (audible && simulated && initialized.compareAndSet(false, true)) {
            SharedReflectionSimulator.WORKER.execute(this::joinReflections);
        }
    }

    /** Client thread, each frame: the direct path and pathing; {@link SharedReflectionSimulator} schedules reflections. */
    public void simulate(AcousticSnapshot snapshot, long now) {
        if (closed || failed || !view.simulated) return;
        latestSnapshot = snapshot;
        View captured = view;
        if (directEngine != null && (now - lastDirect >= 8_000_000
                || AcousticUpdateGate.geometryChanged(this, snapshot, false)) && directBusy.compareAndSet(false, true)) {
            lastDirect = now;
            // Pathing rides on the direct job: it needs the direct path's occlusion of the same pose.
            if (!AcousticUpdateGate.shouldSimulate(this, snapshot, captured.source, captured.listener, false)
                    && !(pathingOwed && now - pathingDueAt >= 0)) {
                directBusy.set(false);
            } else {
                DIRECT.execute(() -> {
                    try {
                        if (!closed && !failed) {
                            View latest = view;
                            AcousticSnapshot scene = latestSnapshot;
                            AcousticUpdateGate.shouldSimulate(this, scene, latest.source, latest.listener, false);
                            var outputs = directEngine.simulate(scene::cast, latest.listener, latest.source, 1, 0);
                            // Steam's 1/d rolloff is far quieter than the vanilla jukebox feel at
                            // range; own the loudness curve here (linear to zero at the audible
                            // edge) and let Steam keep occlusion/transmission/air absorption.
                            outputs.direct.distance = distanceGain(latest.listener.distanceTo(latest.source));
                            if (!closed && !failed) {
                                directOutputs = outputs;
                                directRevision = scene.revision();
                                directPublishedAt = System.nanoTime();
                            }
                            updatePathing(scene, latest, outputs.direct.occlusion);
                        }
                    } catch (RuntimeException | Error error) { fail(error); }
                    finally { directBusy.set(false); }
                });
            }
        }
    }

    /** The diffracted path the renderer currently adds, or null; for the bake view. */
    public PathingField pathingField() { return pathingField; }

    /**
     * Client thread: the radio's baked pathing, or null. A stale bake predates an edit in its
     * region: its routes are re-traced (and dropped where blocked) at a slower cadence.
     */
    public void setPathing(PathingBake bake, boolean stale) {
        var next = bake == null ? null : new AcousticBakeScheduler.Pathing(bake, stale);
        var current = pathing;
        if (current == null ? next == null : next != null && current.bake() == next.bake() && current.stale() == next.stale()) return;
        pathing = next;
        pathingDueAt = System.nanoTime();
        pathingOwed = true;
    }

    /**
     * Direct worker, after a direct run: swaps in a changed bake, then runs pathing at most every
     * {@link #PATHING_INTERVAL} ({@link #STALE_PATHING_INTERVAL} while validating) and publishes
     * the shaped path. A run skipped by the cadence is owed and comes with the next direct job.
     */
    private void updatePathing(AcousticSnapshot scene, View latest, float occlusion) {
        var wanted = pathing;
        PathingBake bake = wanted == null ? null : wanted.bake();
        if (bake != attachedBake) {
            directEngine.attachPathing(bake == null ? null : bake.batch());
            attachedBake = bake;
            lastPathing = System.nanoTime() - STALE_PATHING_INTERVAL;
        }
        if (bake == null) {
            pathingField = null;
            pathingOwed = false;
            return;
        }
        long now = System.nanoTime();
        long interval = wanted.stale() ? STALE_PATHING_INTERVAL : PATHING_INTERVAL;
        if (now - lastPathing < interval) {
            pathingDueAt = lastPathing + interval;
            pathingOwed = true;
            return;
        }
        lastPathing = now;
        pathingOwed = false;
        var raw = directEngine.runPathing(scene::cast, bake.origin(), latest.listener, latest.source, wanted.stale());
        float coverage = AcousticPathing.coverage(latest.listener.distanceTo(Vec3.atCenterOf(bake.radio())));
        PathingField field = AcousticPathing.shape(raw.eq(), raw.sh(), occlusion, coverage, RadioSimulationSession::distanceGain);
        if (!closed && !failed) pathingField = field;
    }

    /** Linear-to-zero loudness out to the acoustic audibility range (vanilla jukebox feel, longer reach). */
    static float distanceGain(double distance) {
        return (float) Math.max(0, 1 - distance / RadioAcousticController.AUDIBLE_RANGE);
    }

    /**
     * Wet level is distance-independent inside rooms (diffuse field), which the simulated IR
     * already captures — so the wet field only gets a constant base gain (live-tunable via
     * -Ddimblend.radio.acoustic.wetgain) times a far taper, so orphaned reverb does not outlive
     * the dry sound past the audible edge. The renderer's limiter handles hot room sums.
     */
    static float wetScale(double distance) {
        return (float) Math.min(1, Math.max(0, (RadioAcousticController.AUDIBLE_RANGE - distance) / 32)) * WET_GAIN.value();
    }

    @Override public AudioFormat format() { return format; }

    @Override public ByteBuffer process(ByteBuffer mono, boolean endOfInput) {
        synchronized (audio) {
            int frames = mono.remaining() / 2;
            boolean tail = frames == 0;
            int inputs = tail ? (tailInput() ? 1 : 0) : (frames + SteamRenderer.FRAME - 1) / SteamRenderer.FRAME;
            // A drained stream still plays out the blocks prepared ahead.
            int outputs = tail ? Math.min(1, queued + inputs) : inputs;
            inputEnded |= endOfInput;
            if (outputs == 0) return ByteBuffer.allocateDirect(0);
            for (int block = 0; block < inputs; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                for (int i = 0; i < input.length && mono.remaining() >= 2; i++) input[i] = mono.getShort() / 32768f;
                submit(new Pending(input, tail));
            }
            AcousticRecording.Track track = recordingTrack();
            ByteBuffer output = ByteBuffer.allocateDirect(outputs * SteamRenderer.FRAME * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int block = 0; block < outputs; block++) {
                float[][] result = null;
                // Until the look-ahead is filled the stream starts with silence.
                if (queued > (tail ? 0 : lookahead)) {
                    queued--;
                    try { result = finish(take(), track); }
                    catch (RuntimeException | Error error) {
                        fail(error);
                        if (track != null) track.played(silentBlock("failed"));
                    }
                } else if (track != null) {
                    track.played(silentBlock("starting"));
                }
                for (int i = 0; i < SteamRenderer.FRAME; i++) {
                    for (int c = 0; c < 2; c++) {
                        float sample = result == null ? 0 : result[c][i];
                        output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * 32767))));
                    }
                }
            }
            return output.flip();
        }
    }

    private AcousticRecording.Played silentBlock(String mode) {
        View now = view;
        return AcousticRecording.Played.silent(mode, now.listener, now.ahead, now.source);
    }

    /** The audio device ran dry; the stream restarts with {@code buffers} queued. */
    @Override public void starved(int buffers) {
        record("starved", () -> String.format(Locale.ROOT, "restarted with %d buffers (%.0f ms)",
                buffers, buffers * SteamRenderer.FRAME * 1000.0 / rate));
    }

    /** Another drained block is worth preparing: delayed direct sound or the reverb tail still sounds. */
    private boolean tailInput() {
        return inputEnded && view.audible && tailLive;
    }

    private void submit(Pending block) {
        pending.add(block);
        queued++;
        if (lookahead > 0 && dspScheduled.compareAndSet(false, true)) DSP.execute(this::stageSubmitted);
    }

    /** DSP thread: stages submitted blocks in order until none are left. */
    private void stageSubmitted() {
        do {
            while (stageNext()) { }
            dspScheduled.set(false);
            // A block submitted between the last poll and the reset found the flag still set.
        } while (!pending.isEmpty() && dspScheduled.compareAndSet(false, true));
    }

    private synchronized boolean stageNext() {
        Pending block = pending.poll();
        if (block == null) return false;
        staged.add(stage(block));
        return true;
    }

    /** The oldest submitted block: staged by the DSP thread, or here if it fell behind. */
    private Staged take() {
        takenInline = false;
        Staged next = staged.poll();
        if (next != null) return next;
        synchronized (this) {
            // Staging polls and publishes under this monitor, so nothing is in between.
            next = staged.poll();
            if (next != null) return next;
            Pending block = pending.poll();
            takenInline = block != null;
            return block == null ? Staged.SILENT : stage(block);
        }
    }

    /** The position-dependent stage of one block; caller holds this monitor. */
    private Staged stage(Pending block) {
        View captured = view;
        Vec3 relative = captured.source.subtract(captured.listener);
        try {
            Staged result = route(block.input, captured, relative, block.tail, AcousticRecording.active() != null);
            directTail = delaying() ? renderer.directTailSamples() : 0;
            tailLive = rendererUsable() && (directTail > 0 || captured.simulated && reflectionTail());
            return result;
        } catch (RuntimeException | Error error) {
            fail(error);
            directTail = 0;
            tailLive = false;
            return Staged.SILENT;
        }
    }

    private boolean reflectionTail() {
        var out = reflectionOutputs;
        return out != null && out.reflections.ir != null && renderer.tailSamples() > 0;
    }

    private boolean rendererUsable() {
        return renderer != null && !closed && !failed;
    }

    /** The last block went through the renderer's propagation delay line. */
    private boolean delaying() {
        return rendererUsable() && (path == Path.RENDERED || path == Path.PANNED && !pannedRaw);
    }

    /**
     * Picks the path of one block and runs its position-dependent part. A path change crossfades
     * across the block from the previous path's output, so selection changes, pauses and failures
     * do not click.
     * <p>
     * While a renderer exists the panner plays its propagation-delayed input, so moving between
     * the two neither skips nor repeats audio. Before the renderer is ready (or after it failed)
     * the panner plays the input undelayed and stays undelayed until the next path change.
     *
     * @param recording a recording runs: the rendered part keeps what it was prepared with
     */
    private Staged route(float[] input, View captured, Vec3 relative, boolean tail, boolean recording) {
        boolean usable = rendererUsable();
        Path next = !captured.audible || closed ? Path.SILENT : usable && captured.simulated ? Path.RENDERED : Path.PANNED;
        Path previous = path == null ? next : path;
        path = next;
        // A failed renderer must not produce the fade-out; fade in from silence instead.
        if (previous == Path.RENDERED && !usable) previous = Path.SILENT;
        if (previous == Path.SILENT && next == Path.SILENT) return Staged.SILENT;
        boolean resetPanner = previous == Path.SILENT;
        if (resetPanner) pannedRaw = false;
        if (usable && next != Path.SILENT) {
            // Out of silence nothing from before the gap may replay; out of the delayed panner
            // the delay line kept running and only the idle native effects are stale.
            if (previous == Path.SILENT) restart(false);
            else if (previous == Path.PANNED && next == Path.RENDERED) restart(!pannedRaw);
        }
        SteamRenderer.Prepared rendered = null;
        float[] panned = null;
        preparedStaging = null;
        if (previous == Path.RENDERED || next == Path.RENDERED) rendered = prepare(input, relative, tail, recording);
        if (previous == Path.PANNED || next == Path.PANNED) {
            panned = input;
            if (!usable || pannedRaw && previous == Path.PANNED) pannedRaw = true;
            else if (rendered != null) panned = renderer.delayedInput().clone();
            else renderer.bypass(panned, relative, tail);
        }
        if (next == Path.RENDERED) pannedRaw = false;
        return new Staged(previous, next, rendered == null ? null : renderer, rendered, panned, resetPanner,
                rendered == null ? null : preparedStaging);
    }

    /**
     * The orientation-dependent stage, with the listener's pose as the block is played.
     *
     * @param track receives the played block, or null while nothing is recorded
     */
    private float[][] finish(Staged block, AcousticRecording.Track track) {
        long started = track == null ? 0 : System.nanoTime();
        View now = view;
        Vec3 relative = now.source.subtract(now.listener);
        float wetScale = wetScale(relative.length());
        SteamRenderer.Stems stems = track != null && block.rendered != null ? new SteamRenderer.Stems() : null;
        float[][] rendered = null, panned = null;
        if (block.rendered != null) {
            rendered = block.renderer.spatialize(block.rendered, relative, orientation(now), wetScale, stems);
        }
        if (block.resetPanner) panner.reset();
        if (block.panned != null) panned = panner.process(block.panned, relative, now.ahead, now.up);
        float[][] from = output(block.previous, rendered, panned), to = output(block.next, rendered, panned);
        float[][] result = block.previous == block.next ? to : crossfade(from, to);
        if (track != null) {
            track.played(new AcousticRecording.Played(block.mode(), takenInline, now.listener, now.ahead, now.source,
                    block.staging, wetScale, rendered == null ? null : stems, result, System.nanoTime() - started));
        }
        return result;
    }

    private static float[][] output(Path path, float[][] rendered, float[][] panned) {
        return path == Path.RENDERED ? rendered : path == Path.PANNED ? panned : null;
    }

    private static float[][] crossfade(float[][] from, float[][] to) {
        float[][] mixed = new float[2][SteamRenderer.FRAME];
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < SteamRenderer.FRAME; i++) {
                float weight = (i + 1f) / SteamRenderer.FRAME;
                mixed[c][i] = (from == null ? 0 : from[c][i] * (1 - weight)) + (to == null ? 0 : to[c][i] * weight);
            }
        }
        return mixed;
    }

    /** Re-entry into the renderer; a reset IR needs a fresh reflection simulation. */
    private void restart(boolean keepDelay) {
        boolean reset = renderer.resume(keepDelay);
        if (reset) {
            reflectionOutputs = null;
            AcousticUpdateGate.invalidateReflections(this);
        }
        record("restart", () -> (keepDelay ? "from panning, delay kept" : "from silence, delay cleared")
                + (reset ? "; effects and IR reset, awaiting a fresh simulation" : ""));
    }

    /** @param recording keep what the block is prepared with in {@link #preparedStaging} */
    private SteamRenderer.Prepared prepare(float[] input, Vec3 relative, boolean tail, boolean recording) {
        long started = recording ? System.nanoTime() : 0;
        var directOut = directOutputs;
        var reflectionOut = reflectionOutputs;
        var direct = directOut == null ? null : directOut.direct;
        var impulse = reflectionOut == null ? null : reflectionOut.reflections;
        PathingField path = pathingField;
        long directAt = directPublishedAt, reflectionAt = reflectionPublishedAt, reflectionCount = reflectionsPublished;
        if (direct == null) {
            direct = new SteamAudio.DirectParams();
        }
        // Distance follows the current pose, not a completed ray job.
        direct.distance = distanceGain(relative.length());
        try {
            SteamRenderer.Prepared prepared = renderer.prepare(input, direct, impulse, relative, tail, path);
            if (recording) {
                long now = System.nanoTime();
                preparedStaging = new AcousticRecording.Staging(prepared.delaySamples(), prepared.directGain(),
                        prepared.directEq(), direct.occlusion, direct.transmission.clone(), direct.air.clone(),
                        directOut == null ? -1 : now - directAt, prepared.reflections(), reflectionCount,
                        reflectionOut == null ? -1 : now - reflectionAt, path, now - started);
            }
            return prepared;
        } finally {
            // The params sub-structures share their parents' backing store.
            Reference.reachabilityFence(directOut);
            Reference.reachabilityFence(reflectionOut);
        }
    }

    private static SteamAudio.Space orientation(View view) {
        var space = new SteamAudio.Space();
        Vec3 right = view.ahead.cross(view.up).normalize();
        space.right = new SteamAudio.Vector(right.x, right.y, right.z);
        space.up = new SteamAudio.Vector(view.up.x, view.up.y, view.up.z);
        space.ahead = new SteamAudio.Vector(view.ahead.x, view.ahead.y, view.ahead.z);
        return space;
    }

    /**
     * After the input ends: blocks prepared ahead, delayed direct sound still in the line, or
     * (when rendered) the reverb tail.
     */
    @Override public boolean hasTail() {
        synchronized (audio) {
            return inputEnded && (queued > 0 || tailInput());
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        record("closed", () -> "");
        AcousticUpdateGate.forget(this);
        directOutputs = null;
        reflectionOutputs = null;
        pathingField = null;
        pending.clear();
        staged.clear();
        DIRECT.execute(() -> { if (directEngine != null) directEngine.close(); });
        SharedReflectionSimulator.WORKER.execute(this::retireReflections);
    }
}
