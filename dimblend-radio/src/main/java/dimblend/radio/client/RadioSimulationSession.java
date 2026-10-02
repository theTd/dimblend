package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.AcousticSnapshot;
import dimblend.radio.acoustics.AcousticUpdateGate;
import dimblend.radio.acoustics.BinauralSpatializer;
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
    private volatile SteamSimulation directEngine, reflectionEngine;
    // JNA embedded sub-structures share the owning SimulationOutputs' native backing store.
    // Pin the owner: if it is collected, the params structs become garbage silently
    // (wet dies, dry params jump around). Consumers pin the holder on the stack.
    private volatile SteamAudio.SimulationOutputs directOutputs, reflectionOutputs;
    private volatile View view = new View(Vec3.ZERO, Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(0, 1, 0), false);
    private volatile boolean closed;
    private volatile boolean failed;
    private SteamRenderer renderer;
    private long lastDirect, lastReflection;
    private boolean inputEnded;
    private boolean gpuEnabled = !"false".equalsIgnoreCase(System.getProperty("dimblend.radio.acoustic.gpu"));

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
        if (closed) return;
        try {
            SteamSimulation engine;
            try { engine = new SteamSimulation(rate, reflections ? 2 : 1, reflections && gpuEnabled); }
            catch (RuntimeException | Error unavailable) {
                if (!reflections || !gpuEnabled) throw unavailable;
                gpuEnabled = false;
                DimBlendRadio.LOGGER.warn("[radio] GPU acoustics unavailable; using reduced CPU tracing", unavailable);
                engine = new SteamSimulation(rate, 2);
            }
            if (closed) { engine.close(); return; }
            if (reflections) reflectionEngine = engine;
            else directEngine = engine;
        } catch (RuntimeException | Error error) { fail(error); }
    }

    private void fail(Throwable error) {
        if (!failed) DimBlendRadio.LOGGER.warn("[radio] acoustic simulation failed; using distance-only sound", error);
        failed = true;
    }

    public void setView(Vec3 source, Vec3 listener, Vec3 ahead, Vec3 up, boolean audible) {
        view = new View(source, listener, ahead, up, audible);
        if (audible && initialized.compareAndSet(false, true)) {
            DIRECT.execute(() -> initialize(false));
            REFLECTIONS.execute(() -> initialize(true));
        }
    }

    public void simulate(AcousticSnapshot snapshot, long now) {
        if (closed || failed || !view.audible) return;
        View captured = view;
        if (directEngine != null && now - lastDirect >= 50_000_000 && directBusy.compareAndSet(false, true)) {
            lastDirect = now;
            if (!AcousticUpdateGate.shouldSimulate(this, snapshot, captured.source, captured.listener, false)) {
                directBusy.set(false);
            } else {
                DIRECT.execute(() -> {
                    try {
                        if (!closed) {
                            var outputs = directEngine.simulate(snapshot::cast, captured.listener, captured.source, 1, 0);
                            // Steam's 1/d rolloff is far quieter than the vanilla jukebox feel at
                            // range; own the loudness curve here (linear to zero at the audible
                            // edge) and let Steam keep occlusion/transmission/air absorption.
                            outputs.direct.distance = distanceGain(captured.listener.distanceTo(captured.source));
                            directOutputs = outputs;
                        }
                    } catch (RuntimeException | Error error) { fail(error); }
                    finally { directBusy.set(false); }
                });
            }
        }
        if (reflectionEngine != null && now - lastReflection >= reflectionInterval() && reflectionBusy.compareAndSet(false, true)) {
            lastReflection = now;
            if (!AcousticUpdateGate.shouldSimulate(this, snapshot, captured.source, captured.listener, true)) {
                reflectionBusy.set(false);
            } else {
                REFLECTIONS.execute(() -> {
                    long start = System.nanoTime();
                    try {
                        if (!closed) {
                            boolean first = reflectionOutputs == null;
                            var geometry = gpuEnabled ? snapshot.mesh(captured.listener, captured.source) : null;
                            SteamSimulation engine = reflectionEngine;
                            SteamAudio.SimulationOutputs outputs;
                            try {
                                // Engines are long-lived: re-run in place; simulateGpu re-uploads
                                // the scene mesh in place when the geometry changed. Replacing the
                                // engine per run was found to NaN the wet field after the second
                                // close-retain cycle and stall the worker on native teardown.
                                outputs = engine.gpu()
                                        ? engine.simulateGpu(geometry, captured.listener, captured.source, 64, 128)
                                        : engine.simulate(snapshot::cast, captured.listener, captured.source, 64, 128);
                            } catch (RuntimeException | Error error) {
                                if (!engine.gpu()) throw error;
                                gpuEnabled = false;
                                DimBlendRadio.LOGGER.warn("[radio] GPU acoustics unavailable; using reduced CPU tracing", error);
                                engine.close();
                                engine = new SteamSimulation(rate, 2);
                                reflectionEngine = engine;
                                // The new engine owns a new context; the renderer's effects belong to
                                // the released one, so rebuild it (next process() frame recreates it).
                                synchronized (RadioSimulationSession.this) {
                                    if (renderer != null) { renderer.close(); renderer = null; }
                                }
                                outputs = engine.simulate(snapshot::cast, captured.listener, captured.source, 64, 128);
                            }
                            synchronized (RadioSimulationSession.this) {
                                if (!closed) {
                                    reflectionOutputs = outputs;
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

    /** GPU scenes are cheap to re-simulate; CPU tracing keeps the slower cadence. */
    private long reflectionInterval() {
        var engine = reflectionEngine;
        return engine != null && engine.gpu() ? 125_000_000L : 250_000_000L;
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
            if (!closed && !failed && renderer == null && reflectionEngine != null) {
                renderer = new SteamRenderer(reflectionEngine.context(), rate);
            }
            if (renderer != null && reflectionEngine != null && !closed)
                BinauralSpatializer.attach(renderer, reflectionEngine.context(), rate);
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
                        direct.distance = distanceGain(relative.length());
                    }
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
        if (renderer != null) { renderer.close(); renderer = null; }
        directOutputs = null;
        reflectionOutputs = null;
        DIRECT.execute(() -> { if (directEngine != null) directEngine.close(); });
        REFLECTIONS.execute(() -> { if (reflectionEngine != null) reflectionEngine.close(); });
    }
}
