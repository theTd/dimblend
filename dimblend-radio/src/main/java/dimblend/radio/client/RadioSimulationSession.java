package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.AcousticSnapshot;
import dimblend.radio.acoustics.AcousticUpdateGate;
import dimblend.radio.acoustics.ReflectionMeshCache;
import dimblend.radio.acoustics.SteamAudio;
import dimblend.radio.acoustics.SteamRenderer;
import dimblend.radio.acoustics.SteamSimulation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import net.minecraft.world.phys.Vec3;

public final class RadioSimulationSession implements RadioPcmProcessor {
    private static final ExecutorService DIRECT = worker("Radio acoustic direct");
    private static final ExecutorService REFLECTIONS = worker("Radio acoustic reflections");
    private record View(Vec3 source, Vec3 listener, Vec3 ahead, Vec3 up, boolean audible) { }
    private final AudioFormat format;
    private final int rate;
    private final AtomicBoolean directBusy = new AtomicBoolean(), reflectionBusy = new AtomicBoolean();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final ReflectionMeshCache meshes = new ReflectionMeshCache();
    private volatile AcousticSnapshot latestSnapshot;
    private volatile SteamSimulation directEngine, reflectionEngine;
    // JNA embedded sub-structures share the owning SimulationOutputs' native backing store.
    // Pin the owner: if it is collected, the params structs become garbage silently
    // (wet dies, dry params jump around). Consumers pin the holder on the stack.
    private volatile SteamAudio.SimulationOutputs directOutputs, reflectionOutputs;
    private volatile long directRevision = -1, reflectionRevision = -1;
    private volatile View view = new View(Vec3.ZERO, Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(0, 1, 0), false);
    private volatile boolean closed;
    private volatile boolean failed;
    private SteamRenderer renderer;
    private long lastDirect, lastReflection;
    private boolean inputEnded;
    private int invalidFields;
    private final boolean gpuEnabled = !"false".equalsIgnoreCase(System.getProperty("dimblend.radio.acoustic.gpu"));

    public RadioSimulationSession(AudioFormat input) {
        rate = Math.round(input.getSampleRate());
        format = new AudioFormat(rate, 16, 2, true, false);
    }

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private void initialize(boolean reflections) {
        if (closed || failed) return;
        try {
            if (!gpuEnabled) throw new IllegalStateException("GPU acoustics disabled");
            SteamSimulation engine = new SteamSimulation(rate, reflections ? 2 : 1, reflections);
            if (reflections) {
                installReflectionEngine(engine);
                // Direct CPU rays are part of the GPU acoustic session, never a fallback.
                if (!closed && !failed) DIRECT.execute(() -> initialize(false));
            } else {
                if (closed || failed) engine.close();
                else directEngine = engine;
            }
        } catch (RuntimeException | Error error) { fail(error); }
    }

    // Only the reflection worker constructs/destroys native resources. Publication and removal
    // share process()'s monitor, so no audio frame can retain a retired source-owned IR pointer.
    private void installReflectionEngine(SteamSimulation engine) {
        SteamRenderer prepared;
        try { prepared = new SteamRenderer(engine.context(), rate, this::reflectionReset); }
        catch (RuntimeException | Error error) { engine.close(); throw error; }
        synchronized (this) {
            if (!closed && !failed) {
                renderer = prepared;
                reflectionOutputs = null;
                reflectionEngine = engine;
                return;
            }
        }
        prepared.close();
        engine.close();
    }

    private void retireReflectionEngine() {
        SteamRenderer oldRenderer;
        SteamSimulation oldEngine;
        synchronized (this) {
            oldRenderer = renderer;
            oldEngine = reflectionEngine;
            renderer = null;
            reflectionEngine = null;
            reflectionOutputs = null;
        }
        try { if (oldRenderer != null) oldRenderer.close(); }
        finally { if (oldEngine != null) oldEngine.close(); }
    }

    private synchronized void reflectionReset() {
        reflectionOutputs = null;
        AcousticUpdateGate.invalidateReflections(this);
        if (++invalidFields >= 3) {
            fail(new IllegalStateException("Repeated invalid GPU reflection output"));
        }
    }

    private synchronized void fail(Throwable error) {
        if (failed) return;
        failed = true;
        directOutputs = null;
        reflectionOutputs = null;
        AcousticUpdateGate.forget(this);
        DimBlendRadio.LOGGER.warn("[radio] acoustics disabled; using distance-only sound (no CPU fallback)", error);
        DIRECT.execute(() -> { if (directEngine != null) { directEngine.close(); directEngine = null; } });
        REFLECTIONS.execute(this::retireReflectionEngine);
    }

    public boolean active() { return !closed && !failed; }

    public void setView(Vec3 source, Vec3 listener, Vec3 ahead, Vec3 up, boolean audible) {
        if (closed) return;
        view = new View(source, listener, ahead, up, audible);
        if (audible && initialized.compareAndSet(false, true)) {
            REFLECTIONS.execute(() -> initialize(true));
        }
    }

    public void simulate(AcousticSnapshot snapshot, long now) {
        if (closed || failed || !view.audible) return;
        latestSnapshot = snapshot;
        View captured = view;
        if (directEngine != null && (now - lastDirect >= 8_000_000
                || AcousticUpdateGate.geometryChanged(this, snapshot, false)) && directBusy.compareAndSet(false, true)) {
            lastDirect = now;
            if (!AcousticUpdateGate.shouldSimulate(this, snapshot, captured.source, captured.listener, false)) {
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
                            }
                        }
                    } catch (RuntimeException | Error error) { fail(error); }
                    finally { directBusy.set(false); }
                });
            }
        }
        if (reflectionEngine != null && (now - lastReflection >= reflectionInterval()
                || AcousticUpdateGate.geometryChanged(this, snapshot, true)) && reflectionBusy.compareAndSet(false, true)) {
            lastReflection = now;
            if (!AcousticUpdateGate.shouldSimulate(this, snapshot, captured.source, captured.listener, true)) {
                reflectionBusy.set(false);
            } else {
                REFLECTIONS.execute(() -> {
                    long start = System.nanoTime();
                    try {
                        if (!closed && !failed) {
                            View latest = view;
                            if (!latest.audible) return;
                            // A block edit can arrive while this radio waits behind another job.
                            AcousticSnapshot scene = latestSnapshot;
                            AcousticUpdateGate.shouldSimulate(this, scene, latest.source, latest.listener, true);
                            boolean first = reflectionOutputs == null;
                            var geometry = meshes.get(scene, latest.listener, latest.source, scene::mesh);
                            SteamSimulation engine = reflectionEngine;
                            if (engine == null || closed || failed) return;
                            SteamAudio.SimulationOutputs outputs;
                            try {
                                // Engines are long-lived: re-run in place; simulateGpu re-uploads
                                // the scene mesh in place when the geometry changed. Replacing the
                                // engine per run was found to NaN the wet field after the second
                                // close-retain cycle and stall the worker on native teardown.
                                outputs = engine.simulateGpu(geometry, latest.listener, latest.source, SteamSimulation.GPU_RAYS, 128);
                            } catch (RuntimeException | Error error) {
                                fail(error);
                                return;
                            }
                            synchronized (RadioSimulationSession.this) {
                                if (!closed && !failed) {
                                    reflectionOutputs = outputs;
                                    reflectionRevision = scene.revision();
                                    renderer.reflectionsReady();
                                }
                            }
                            if (first) DimBlendRadio.LOGGER.info("[radio] {} convolution IR ready: {} channels, {} samples, {} ms",
                                    engine.gpu() ? "GPU" : "CPU", outputs.reflections.channels, outputs.reflections.irSize,
                                    (System.nanoTime() - start) / 1_000_000);
                            DimBlendRadio.LOGGER.debug("[radio] {} acoustic IR in {} ms ({} tris)",
                                    engine.gpu() ? "GPU" : "CPU", (System.nanoTime() - start) / 1_000_000,
                                    geometry == null ? -1 : geometry.triangles().length / 3);
                        }
                    } catch (RuntimeException | Error error) { fail(error); }
                    finally { reflectionBusy.set(false); }
                });
            }
        }
    }

    /** Motion updates are scheduled from the latest camera pose, independently of client ticks. */
    private long reflectionInterval() {
        return 50_000_000L;
    }

    /** Linear-to-zero loudness out to the acoustic audibility range (vanilla jukebox feel, longer reach). */
    static float distanceGain(double distance) {
        return (float) Math.max(0, 1 - distance / RadioAcousticController.AUDIBLE_RANGE);
    }

    /**
     * Wet level is distance-independent inside rooms (diffuse field), which the simulated IR
     * already captures — so the wet field only gets a constant base gain (live-tunable via
     * -Ddimblend.radio.acoustic.wetgain) times a far taper, so orphaned reverb does not outlive
     * the dry sound past the audible edge. The block limiter handles hot room sums.
     */
    static float wetScale(double distance) {
        float base = Float.parseFloat(System.getProperty("dimblend.radio.acoustic.wetgain", "3"));
        return (float) Math.min(1, Math.max(0, (RadioAcousticController.AUDIBLE_RANGE - distance) / 32)) * base;
    }

    @Override public AudioFormat format() { return format; }

    @Override public synchronized ByteBuffer process(ByteBuffer mono, boolean endOfInput) {
        int frames = mono.remaining() / 2;
        if (frames == 0 && !hasTail()) return ByteBuffer.allocateDirect(0);
        int blocks = Math.max(1, (frames + SteamRenderer.FRAME - 1) / SteamRenderer.FRAME);
        ByteBuffer output = ByteBuffer.allocateDirect(blocks * SteamRenderer.FRAME * 4).order(ByteOrder.LITTLE_ENDIAN);
        View captured = view;
        Vec3 relative = captured.source.subtract(captured.listener);
        try {
            for (int block = 0; block < blocks; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                for (int i = 0; i < input.length && mono.remaining() >= 2; i++) input[i] = mono.getShort() / 32768f;
                float[][] result;
                if (renderer != null && !closed && !failed && captured.audible) {
                    // Pin the JNA parents on the stack for the whole render call: the params
                    // sub-structures share their backing store.
                    var directOut = directOutputs;
                    var reflectionOut = reflectionOutputs;
                    var direct = directOut == null ? null : directOut.direct;
                    var impulse = reflectionOut == null ? null : reflectionOut.reflections;
                    if (direct == null) {
                        direct = new SteamAudio.DirectParams();
                    }
                    // Distance follows the current audio-frame pose, not a completed ray job.
                    direct.distance = distanceGain(relative.length());
                    result = renderer.render(input, direct, impulse, relative, orientation(captured), frames == 0,
                            wetScale(relative.length()));
                } else {
                    result = new float[2][input.length];
                    float gain = distanceGain(relative.length()) * 0.7071f;
                    for (int i = 0; i < input.length; i++) result[0][i] = result[1][i] = input[i] * gain;
                }
                for (int i = 0; i < input.length; i++) {
                    for (int c = 0; c < 2; c++) {
                        float sample = captured.audible && !closed ? result[c][i] : 0;
                        output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * 32767))));
                    }
                }
            }
        } catch (RuntimeException | Error error) {
            fail(error);
            while (output.hasRemaining()) output.put((byte) 0);
        }
        inputEnded |= endOfInput;
        return output.flip();
    }

    private static SteamAudio.Space orientation(View view) {
        var space = new SteamAudio.Space();
        Vec3 right = view.ahead.cross(view.up).normalize();
        space.right = new SteamAudio.Vector(right.x, right.y, right.z);
        space.up = new SteamAudio.Vector(view.up.x, view.up.y, view.up.z);
        space.ahead = new SteamAudio.Vector(view.ahead.x, view.ahead.y, view.ahead.z);
        return space;
    }

    @Override public synchronized boolean hasTail() {
        var out = reflectionOutputs;
        return inputEnded && view.audible && !closed && !failed && renderer != null
                && out != null && out.reflections.ir != null && renderer.tailSamples() > 0;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        AcousticUpdateGate.forget(this);
        directOutputs = null;
        reflectionOutputs = null;
        DIRECT.execute(() -> { if (directEngine != null) directEngine.close(); });
        REFLECTIONS.execute(this::retireReflectionEngine);
    }
}
