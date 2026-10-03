package dimblend.radio.acoustics;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import dimblend.radio.DimBlendRadio;
import net.minecraft.world.phys.Vec3;

/**
 * Native convolution of simulated IRs, plus direct occlusion/transmission, the baked diffracted
 * path ({@link PathingField}) and spatial decoding.
 * <p>
 * A block runs in two stages. {@link #prepare} (propagation delay, occlusion filter, reflection
 * convolution) depends on the source position only and may run ahead on a DSP thread;
 * {@link #spatialize} (direction, HRTF/panning, ambisonics decode, limiter) takes the listener's
 * orientation at playback time, so head turns are not delayed by the look-ahead. Each stage must
 * be called in block order by one thread at a time, but the two stages may run concurrently.
 */
public final class SteamRenderer implements AutoCloseable {
    public static final int FRAME = 512;
    /** Output ceiling; the limiter's soft knee starts here. */
    private static final float LIMIT = 0.95f;
    private final Runnable requestReflections;
    private int invalidFields;
    private boolean awaitingReflections;
    /** Debug-gated wet/dry signal meters (read by live probes; averaged since last poll). */
    private static final java.util.concurrent.atomic.DoubleAdder WET_ENERGY = new java.util.concurrent.atomic.DoubleAdder();
    private static final java.util.concurrent.atomic.DoubleAdder WET_PRE_DECODE = new java.util.concurrent.atomic.DoubleAdder();
    private static final java.util.concurrent.atomic.DoubleAdder DRY_ENERGY = new java.util.concurrent.atomic.DoubleAdder();
    private static final java.util.concurrent.atomic.LongAdder METER_SAMPLES = new java.util.concurrent.atomic.LongAdder();

    /** @return {wetRmsPostDecode, dryRms, wetRmsPreDecode} averaged since the last poll. */
    public static double[] pollRms() {
        double wet = WET_ENERGY.sumThenReset();
        double wetPre = WET_PRE_DECODE.sumThenReset();
        double dry = DRY_ENERGY.sumThenReset();
        long samples = METER_SAMPLES.sumThenReset();
        if (samples == 0) return new double[] {0, 0, 0};
        return new double[] {Math.sqrt(wet / samples), Math.sqrt(dry / samples), Math.sqrt(wetPre / (samples * 2))};
    }
    private final SteamAudio.Api api = SteamAudio.api();
    private final PointerByReference direct = new PointerByReference(), reflections = new PointerByReference();
    private final PointerByReference panning = new PointerByReference(), decoding = new PointerByReference();
    private final PointerByReference pathing = new PointerByReference();
    // Prepare stage: buffers and scratch are reused, one block at a time.
    private final SteamAudio.AudioBuffer delayedInput = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer dry = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer wet = new SteamAudio.AudioBuffer(4);
    private final SteamAudio.AudioBuffer path = new SteamAudio.AudioBuffer(4);
    private final SteamAudio.PathParams pathParams = new SteamAudio.PathParams();
    private final com.sun.jna.Memory pathCoefficients = new com.sun.jna.Memory(4 * 4);
    /** The path rendered in the previous block; null when the path effect is silent and reset. */
    private PathingField lastPath;
    private final float[] directSamples = new float[FRAME];
    private final float[] delayed = new float[FRAME];
    private final PropagationDelayLine propagation;
    private final DirectSoundGain directGain;
    /** Samples of received input still inside the propagation delay line. */
    private int pendingInput;
    /** History that {@link #resume(boolean)} must discard: delay line contents, native effect state. */
    private boolean delayUsed, effectsUsed;
    /** A resume reset the prepare stage; the next prepared block carries the reset to the spatial stage. */
    private boolean resetSpatial;
    // Spatial stage, guarded by spatialLock (which also orders it against close()).
    // Embedded Structures receive copied values; assigning one would re-point its memory.
    private final Object spatialLock = new Object();
    private final SteamAudio.AudioBuffer drySpatial = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer wetSpatial = new SteamAudio.AudioBuffer(4);
    private final SteamAudio.AudioBuffer dryStereo = new SteamAudio.AudioBuffer(2), wetStereo = new SteamAudio.AudioBuffer(2);
    private final SteamAudio.PanningParams pan = new SteamAudio.PanningParams();
    private final SteamAudio.DecodeParams decode = new SteamAudio.DecodeParams();
    private final float[] dryScratch = new float[FRAME], wetScratch = new float[FRAME], mixScratch = new float[FRAME];
    private float limiterGain = 1;
    private final int rate;
    private final float limiterRelease;
    private volatile boolean closed;

    /** One block after propagation, occlusion and convolution: everything but the listener's orientation. */
    public static final class Prepared {
        private final float[] dry = new float[FRAME];
        private final float[][] wet = new float[4][FRAME];
        /** The diffracted path in world-space ambisonics, or null when there is none. */
        private float[][] path;
        /** The spatial stage restarts here: its effects and limiter hold state from before a pause. */
        private final boolean resetSpatial;

        private Prepared(boolean resetSpatial) { this.resetSpatial = resetSpatial; }
    }

    public SteamRenderer(Pointer context, int rate) {
        this(context, rate, () -> { });
    }

    public SteamRenderer(Pointer context, int rate, Runnable requestReflections) {
        this.requestReflections = requestReflections;
        this.rate = rate;
        propagation = new PropagationDelayLine(rate);
        directGain = new DirectSoundGain((double) FRAME / rate);
        limiterRelease = (float) (1 - Math.exp(-1.0 / rate)); // ~1 s time constant, applied per sample
        var audio = new SteamAudio.AudioSettings();
        audio.samplingRate = rate;
        try {
            SteamAudio.check(api.iplDirectEffectCreate(context, audio, new SteamAudio.DirectSettings(), direct), "direct effect");
            var settings = new SteamAudio.ReflectionSettings();
            settings.irSize = rate * 6;
            settings.channels = 4;
            SteamAudio.check(api.iplReflectionEffectCreate(context, audio, settings, reflections), "convolution effect");
            SteamAudio.check(api.iplPanningEffectCreate(context, audio, new SteamAudio.PanningSettings(), panning), "direct spatializer");
            SteamAudio.check(api.iplAmbisonicsDecodeEffectCreate(context, audio, new SteamAudio.DecodeSettings(), decoding), "reflection decoder");
            // World-space ambisonics out: decoded with the reflections, so head turns need no re-run.
            SteamAudio.check(api.iplPathEffectCreate(context, audio, new SteamAudio.PathEffectSettings(), pathing), "path effect");
            pathParams.order = 1;
            pathParams.coefficients = pathCoefficients;
            BinauralSpatializer.attach(this, context, rate);
        } catch (RuntimeException | Error error) { close(); throw error; }
    }

    /** Both stages at once, for single-threaded callers. */
    public float[][] render(float[] samples, SteamAudio.DirectParams directParams,
            SteamAudio.ReflectionParams impulse, Vec3 relativeSource, SteamAudio.Space orientation, boolean tail,
            float wetGain) {
        return spatialize(prepare(samples, directParams, impulse, relativeSource, tail), relativeSource, orientation, wetGain);
    }

    /**
     * The orientation-independent stage of one block. Reflections are convolved into world-aligned
     * ambisonics, so the costly convolution does not depend on where the listener is looking.
     */
    public Prepared prepare(float[] samples, SteamAudio.DirectParams directParams,
            SteamAudio.ReflectionParams impulse, Vec3 relativeSource, boolean tail) {
        return prepare(samples, directParams, impulse, relativeSource, tail, null);
    }

    /**
     * As {@link #prepare(float[], SteamAudio.DirectParams, SteamAudio.ReflectionParams, Vec3, boolean)}
     * with the diffracted path rendered from the same delayed input.
     *
     * @param pathField the path to the listener now, or null when there is none
     */
    public Prepared prepare(float[] samples, SteamAudio.DirectParams directParams,
            SteamAudio.ReflectionParams impulse, Vec3 relativeSource, boolean tail, PathingField pathField) {
        if (samples.length != FRAME) throw new IllegalArgumentException("Expected one native audio frame");
        Prepared block = new Prepared(resetSpatial);
        resetSpatial = false;
        if (closed) return block;
        effectsUsed = true;
        // Reverb tail only once everything received has left the propagation line.
        boolean drained = tail && pendingInput <= 0;
        // Propagation delays emitted PCM, not the listener's current occlusion/distance state.
        // Filtering before the line makes a newly blocked path keep playing old clear audio.
        // Steam Audio times reflection responses from the direct arrival, so the convolution
        // takes the same delayed PCM: reflections follow the direct sound, never precede it.
        System.arraycopy(samples, 0, directSamples, 0, FRAME);
        delay(directSamples, relativeSource, tail);
        System.arraycopy(directSamples, 0, delayed, 0, FRAME);
        delayedInput.memory(0).write(0, directSamples, 0, FRAME);
        float targetGain = directGain.prepare(directParams);
        api.iplDirectEffectApply(direct.getValue(), directGain.equalization(), delayedInput, dry);
        dry.memory(0).read(0, block.dry, 0, FRAME);
        directGain.apply(block.dry, targetGain, rate);
        for (int c = 0; c < 4; c++) wet.memory(c).clear();
        if (!awaitingReflections && impulse != null && impulse.ir != null) {
            if (drained) api.iplReflectionEffectGetTail(reflections.getValue(), wet, null);
            else api.iplReflectionEffectApply(reflections.getValue(), impulse, delayedInput, wet, null);
        }
        // Non-finite wet (a NaN/Inf IR, whatever its source) must never reach the mix: it would
        // poison the limiter and silence the dry path too. Suppress and reset the effect.
        boolean poisoned = false;
        for (int c = 0; c < 4; c++) {
            wet.memory(c).read(0, block.wet[c], 0, FRAME);
            for (float sample : block.wet[c]) {
                if (!Float.isFinite(sample)) { poisoned = true; break; }
            }
        }
        if (poisoned) {
            for (float[] channel : block.wet) java.util.Arrays.fill(channel, 0);
            resetReflections();
            DimBlendRadio.LOGGER.warn("[radio] non-finite reflection field #{}; requesting fresh simulation", ++invalidFields);
        }
        block.path = renderPath(pathField);
        return block;
    }

    /**
     * One block of the path effect on the delayed input. A path that appears fades in over the
     * block and one that disappears fades out over the next (rendered with its last values), so
     * neither steps; the effect is then reset and costs nothing until a path returns.
     */
    private float[][] renderPath(PathingField field) {
        if (field == null && lastPath == null) return null;
        boolean fadeIn = lastPath == null, fadeOut = field == null;
        PathingField current = fadeOut ? lastPath : field;
        System.arraycopy(current.eq(), 0, pathParams.eq, 0, 3);
        pathCoefficients.write(0, current.sh(), 0, 4);
        api.iplPathEffectApply(pathing.getValue(), pathParams, delayedInput, path);
        float[][] output = new float[4][FRAME];
        boolean poisoned = false;
        for (int c = 0; c < 4; c++) {
            path.memory(c).read(0, output[c], 0, FRAME);
            for (float sample : output[c]) {
                if (!Float.isFinite(sample)) { poisoned = true; break; }
            }
        }
        if (poisoned) {
            api.iplPathEffectReset(pathing.getValue());
            lastPath = null;
            DimBlendRadio.LOGGER.warn("[radio] non-finite path field; path effect reset");
            return null;
        }
        if (fadeIn || fadeOut) {
            for (int i = 0; i < FRAME; i++) {
                float ramp = (i + 1f) / FRAME;
                float gain = fadeIn ? ramp : 1 - ramp;
                for (int c = 0; c < 4; c++) output[c][i] *= gain;
            }
        }
        if (fadeOut) {
            api.iplPathEffectReset(pathing.getValue());
            lastPath = null;
        } else {
            lastPath = field;
        }
        return output;
    }

    /**
     * The orientation-dependent stage of a prepared block, in block order.
     * @return stereo output, or {@code null} once the renderer is closed
     */
    public float[][] spatialize(Prepared block, Vec3 relativeSource, SteamAudio.Space orientation, float wetGain) {
        synchronized (spatialLock) {
            if (closed) return null;
            if (block.resetSpatial) {
                BinauralSpatializer.reset(this);
                api.iplPanningEffectReset(panning.getValue());
                api.iplAmbisonicsDecodeEffectReset(decoding.getValue());
                limiterGain = 1;
            }
            boolean meter = Boolean.getBoolean("dimblend.radio.acoustic.debug");
            drySpatial.memory(0).write(0, block.dry, 0, FRAME);
            Vec3 direction = relativeSource.normalize();
            if (direction.lengthSqr() < 0.001) direction = new Vec3(0, 0, -1);
            pan.direction.set(dot(direction, orientation.right), dot(direction, orientation.up), -dot(direction, orientation.ahead));
            if (!BinauralSpatializer.direct(this, pan.direction, drySpatial, dryStereo))
                api.iplPanningEffectApply(panning.getValue(), pan, drySpatial, dryStereo);
            double wetPre = 0;
            // Reflections and the diffracted path share one decode; both are world-space ambisonics.
            for (int c = 0; c < 4; c++) {
                float[] reflected = block.wet[c], diffracted = block.path == null ? null : block.path[c];
                for (int i = 0; i < FRAME; i++) mixScratch[i] = reflected[i] * wetGain + (diffracted == null ? 0 : diffracted[i]);
                wetSpatial.memory(c).write(0, mixScratch, 0, FRAME);
                if (meter) for (float sample : reflected) wetPre += sample * (double) sample;
            }
            if (meter) WET_PRE_DECODE.add(wetPre);
            decode.orientation.set(orientation);
            if (!BinauralSpatializer.reflections(this, orientation, wetSpatial, wetStereo))
                api.iplAmbisonicsDecodeEffectApply(decoding.getValue(), decode, wetSpatial, wetStereo);
            float[][] output = new float[2][FRAME];
            float peak = 0;
            double wetEnergy = 0, dryEnergy = 0;
            for (int c = 0; c < 2; c++) {
                float[] d = dryScratch, w = wetScratch;
                dryStereo.memory(c).read(0, d, 0, FRAME);
                wetStereo.memory(c).read(0, w, 0, FRAME);
                for (int i = 0; i < FRAME; i++) {
                    if (meter) { wetEnergy += w[i] * (double) w[i]; dryEnergy += d[i] * (double) d[i]; }
                    output[c][i] = d[i] + w[i];
                    peak = Math.max(peak, Math.abs(output[c][i]));
                }
            }
            if (meter) {
                WET_ENERGY.add(wetEnergy);
                DRY_ENERGY.add(dryEnergy);
                METER_SAMPLES.add(FRAME * 2L);
            }
            limit(output, peak);
            return output;
        }
    }

    /**
     * Reflective rooms can legitimately sum past full scale. The gain moves per sample (a linear
     * attack across the block down to the block-peak target, ~1 s release), so it never steps at a
     * block edge; a soft knee rounds off peaks the attack ramp has not reached yet.
     */
    private void limit(float[][] output, float peak) {
        float target = peak > LIMIT ? LIMIT / peak : 1;
        float start = limiterGain, gain = start;
        boolean attack = target < start;
        for (int i = 0; i < FRAME; i++) {
            gain = attack ? start + (target - start) * (i + 1f) / FRAME : gain + (target - gain) * limiterRelease;
            for (int c = 0; c < 2; c++) output[c][i] = softLimit(output[c][i] * gain);
        }
        limiterGain = gain;
    }

    /** Identity up to {@link #LIMIT}; above it, approaches full scale asymptotically. */
    static float softLimit(float sample) {
        float magnitude = Math.abs(sample);
        if (magnitude <= LIMIT) return sample;
        return Math.copySign(LIMIT + (1 - LIMIT) * (float) Math.tanh((magnitude - LIMIT) / (1 - LIMIT)), sample);
    }

    private static double dot(Vec3 direction, SteamAudio.Vector axis) {
        return direction.x * axis.x + direction.y * axis.y + direction.z * axis.z;
    }

    /** @param tail no input arrived for this block: the line drains instead of refilling */
    private void delay(float[] samples, Vec3 relativeSource, boolean tail) {
        delayUsed = true;
        propagation.process(samples, relativeSource.length());
        pendingInput = tail ? Math.max(0, pendingInput - FRAME) : propagation.pendingSamples();
    }

    /**
     * Advances only the propagation delay, for a block spatialized elsewhere: {@code samples} is
     * replaced by its delayed copy, so moving between that path and {@link #render} neither skips
     * nor repeats audio. The idle native effects go stale; {@link #resume(boolean)} resets them.
     */
    public void bypass(float[] samples, Vec3 relativeSource, boolean tail) {
        if (samples.length != FRAME) throw new IllegalArgumentException("Expected one native audio frame");
        delay(samples, relativeSource, tail);
    }

    /** The last rendered block after the propagation delay, before occlusion; reused per block. */
    public float[] delayedInput() { return delayed; }

    public int tailSamples() { return api.iplReflectionEffectGetTailSize(reflections.getValue()); }

    /** Samples of already-received input still inside the propagation delay line. */
    public int directTailSamples() { return delayUsed ? pendingInput : 0; }

    /**
     * Discards history before rendering again, so stale audio cannot replay: the native filter
     * and convolution state (with its IR) whenever {@link #prepare} ran before, and the delay line
     * unless {@code keepDelay} (it kept running through {@link #bypass}). After a reset the wet
     * path stays silent until the caller publishes a fresh simulation. Called in the prepare
     * stage; the spatial stage resets when it reaches the next prepared block.
     * @return true if the effects were reset, so a fresh reflection simulation is needed
     */
    public boolean resume(boolean keepDelay) {
        if (!keepDelay && delayUsed) {
            delayUsed = false;
            pendingInput = 0;
            propagation.clear();
        }
        if (!effectsUsed) return false;
        effectsUsed = false;
        api.iplDirectEffectReset(direct.getValue());
        api.iplReflectionEffectReset(reflections.getValue());
        api.iplPathEffectReset(pathing.getValue());
        lastPath = null;
        awaitingReflections = true;
        directGain.reset();
        resetSpatial = true;
        return true;
    }

    /** A reset discards the active native IR; reusing its old parameters cannot restore it. */
    public void resetReflections() {
        api.iplReflectionEffectReset(reflections.getValue());
        awaitingReflections = true;
        requestReflections.run();
    }

    /** Called under the audio owner's lock only after the worker publishes a fresh simulation. */
    public void reflectionsReady() { awaitingReflections = false; }

    /** The owner stops preparing first; a spatial stage still holding blocks gets {@code null} from then on. */
    @Override public void close() {
        synchronized (spatialLock) {
            closed = true;
            BinauralSpatializer.detach(this);
            if (decoding.getValue() != null) api.iplAmbisonicsDecodeEffectRelease(decoding);
            if (panning.getValue() != null) api.iplPanningEffectRelease(panning);
            if (pathing.getValue() != null) api.iplPathEffectRelease(pathing);
            if (reflections.getValue() != null) api.iplReflectionEffectRelease(reflections);
            if (direct.getValue() != null) api.iplDirectEffectRelease(direct);
        }
    }
}
