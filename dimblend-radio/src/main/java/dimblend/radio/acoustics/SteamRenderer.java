package dimblend.radio.acoustics;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import dimblend.radio.DimBlendRadio;
import net.minecraft.world.phys.Vec3;

/** Native convolution of simulated IRs, plus direct occlusion/transmission and spatial decoding. */
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
    private final SteamAudio.AudioBuffer delayedInput = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer dry = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer wet = new SteamAudio.AudioBuffer(4);
    private final SteamAudio.AudioBuffer dryStereo = new SteamAudio.AudioBuffer(2), wetStereo = new SteamAudio.AudioBuffer(2);
    // Per-block scratch, reused: the owner renders one block at a time under its audio lock.
    // Embedded Structures receive copied values; assigning one would re-point its memory.
    private final SteamAudio.PanningParams pan = new SteamAudio.PanningParams();
    private final SteamAudio.DecodeParams decode = new SteamAudio.DecodeParams();
    private final float[] directSamples = new float[FRAME], dryScratch = new float[FRAME], wetScratch = new float[FRAME];
    private final float[] delayed = new float[FRAME];
    private final PropagationDelayLine propagation;
    private final int rate;
    private final float limiterRelease;
    private final DirectSoundGain directGain = new DirectSoundGain();
    private float limiterGain = 1;
    /** Samples of received input still inside the propagation delay line. */
    private int pendingInput;
    /** History that {@link #resume(boolean)} must discard: delay line contents, native effect state. */
    private boolean delayUsed, effectsUsed;

    public SteamRenderer(Pointer context, int rate) {
        this(context, rate, () -> { });
    }

    public SteamRenderer(Pointer context, int rate, Runnable requestReflections) {
        this.requestReflections = requestReflections;
        this.rate = rate;
        propagation = new PropagationDelayLine(rate);
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
            BinauralSpatializer.attach(this, context, rate);
        } catch (RuntimeException | Error error) { close(); throw error; }
    }

    public float[][] render(float[] samples, SteamAudio.DirectParams directParams,
            SteamAudio.ReflectionParams impulse, Vec3 relativeSource, SteamAudio.Space orientation, boolean tail,
            float wetGain) {
        if (samples.length != FRAME) throw new IllegalArgumentException("Expected one native audio frame");
        boolean meter = Boolean.getBoolean("dimblend.radio.acoustic.debug");
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
        dry.memory(0).read(0, directSamples, 0, FRAME);
        directGain.apply(directSamples, targetGain, rate);
        dry.memory(0).write(0, directSamples, 0, FRAME);
        Vec3 direction = relativeSource.normalize();
        if (direction.lengthSqr() < 0.001) direction = new Vec3(0, 0, -1);
        pan.direction.set(dot(direction, orientation.right), dot(direction, orientation.up), -dot(direction, orientation.ahead));
        if (!BinauralSpatializer.direct(this, pan.direction, dry, dryStereo))
            api.iplPanningEffectApply(panning.getValue(), pan, dry, dryStereo);
        for (int c = 0; c < 4; c++) wet.memory(c).clear();
        if (!awaitingReflections && impulse != null && impulse.ir != null) {
            if (drained) api.iplReflectionEffectGetTail(reflections.getValue(), wet, null);
            else api.iplReflectionEffectApply(reflections.getValue(), impulse, delayedInput, wet, null);
        }
        // Non-finite wet (a NaN/Inf IR, whatever its source) must never reach the mix: it would
        // poison the limiter and silence the dry path too. Suppress and reset the effect.
        boolean poisoned = false;
        for (int c = 0; c < 4 && !poisoned; c++) {
            wet.memory(c).read(0, wetScratch, 0, FRAME);
            for (float sample : wetScratch) {
                if (!Float.isFinite(sample)) { poisoned = true; break; }
            }
        }
        if (poisoned) {
            for (int c = 0; c < 4; c++) wet.memory(c).clear();
            resetReflections();
            DimBlendRadio.LOGGER.warn("[radio] non-finite reflection field #{}; requesting fresh simulation", ++invalidFields);
        }
        decode.orientation.set(orientation);
        if (meter) {
            double wetPre = 0;
            for (int c = 0; c < 4; c++) {
                wet.memory(c).read(0, wetScratch, 0, FRAME);
                for (float sample : wetScratch) wetPre += sample * (double) sample;
            }
            WET_PRE_DECODE.add(wetPre);
        }
        if (!BinauralSpatializer.reflections(this, orientation, wet, wetStereo))
            api.iplAmbisonicsDecodeEffectApply(decoding.getValue(), decode, wet, wetStereo);
        float[][] output = new float[2][FRAME];
        float peak = 0;
        double wetEnergy = 0, dryEnergy = 0;
        for (int c = 0; c < 2; c++) {
            float[] d = dryScratch, w = wetScratch;
            dryStereo.memory(c).read(0, d, 0, FRAME);
            wetStereo.memory(c).read(0, w, 0, FRAME);
            for (int i = 0; i < FRAME; i++) {
                if (meter) { wetEnergy += w[i] * (double) w[i]; dryEnergy += d[i] * (double) d[i]; }
                output[c][i] = d[i] + w[i] * wetGain;
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
     * and convolution state (with its IR) whenever {@link #render} ran before, and the delay line
     * unless {@code keepDelay} (it kept running through {@link #bypass}). After a reset the wet
     * path stays silent until the caller publishes a fresh simulation.
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
        BinauralSpatializer.reset(this);
        api.iplDirectEffectReset(direct.getValue());
        api.iplPanningEffectReset(panning.getValue());
        api.iplAmbisonicsDecodeEffectReset(decoding.getValue());
        api.iplReflectionEffectReset(reflections.getValue());
        awaitingReflections = true;
        directGain.reset();
        limiterGain = 1;
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

    @Override public void close() {
        BinauralSpatializer.detach(this);
        if (decoding.getValue() != null) api.iplAmbisonicsDecodeEffectRelease(decoding);
        if (panning.getValue() != null) api.iplPanningEffectRelease(panning);
        if (reflections.getValue() != null) api.iplReflectionEffectRelease(reflections);
        if (direct.getValue() != null) api.iplDirectEffectRelease(direct);
    }
}
