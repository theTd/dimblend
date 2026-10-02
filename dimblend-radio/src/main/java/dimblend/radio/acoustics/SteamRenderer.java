package dimblend.radio.acoustics;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import dimblend.radio.DimBlendRadio;
import net.minecraft.world.phys.Vec3;

/** Native convolution of simulated IRs, plus direct occlusion/transmission and spatial decoding. */
public final class SteamRenderer implements AutoCloseable {
    public static final int FRAME = 512;
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
    private final SteamAudio.AudioBuffer input = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer delayedInput = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer dry = new SteamAudio.AudioBuffer();
    private final SteamAudio.AudioBuffer wet = new SteamAudio.AudioBuffer(4);
    private final SteamAudio.AudioBuffer dryStereo = new SteamAudio.AudioBuffer(2), wetStereo = new SteamAudio.AudioBuffer(2);
    private final float[] delayLine;
    private final int rate;
    private final DirectSoundGain directGain = new DirectSoundGain();
    private int delayCursor;
    private float limiterGain = 1;

    public SteamRenderer(Pointer context, int rate) {
        this(context, rate, () -> { });
    }

    public SteamRenderer(Pointer context, int rate, Runnable requestReflections) {
        this.requestReflections = requestReflections;
        this.rate = rate;
        delayLine = new float[rate];
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
        input.memory(0).write(0, samples, 0, FRAME);
        // Propagation delays emitted PCM, not the listener's current occlusion/distance state.
        // Filtering before the line makes a newly blocked path keep playing old clear audio.
        float[] directSamples = samples.clone();
        double delay = Math.min(delayLine.length - 2, relativeSource.length() / 343 * rate);
        BinauralSpatializer.delay(this, delayLine, delayCursor, directSamples, delay);
        delayCursor = (delayCursor + FRAME) % delayLine.length;
        delayedInput.memory(0).write(0, directSamples, 0, FRAME);
        float targetGain = directGain.prepare(directParams);
        api.iplDirectEffectApply(direct.getValue(), directGain.equalization(), delayedInput, dry);
        dry.memory(0).read(0, directSamples, 0, FRAME);
        directGain.apply(directSamples, targetGain, rate);
        dry.memory(0).write(0, directSamples, 0, FRAME);
        var pan = new SteamAudio.PanningParams();
        Vec3 direction = relativeSource.normalize();
        if (direction.lengthSqr() < 0.001) direction = new Vec3(0, 0, -1);
        Vec3 right = new Vec3(orientation.right.x, orientation.right.y, orientation.right.z);
        Vec3 up = new Vec3(orientation.up.x, orientation.up.y, orientation.up.z);
        Vec3 ahead = new Vec3(orientation.ahead.x, orientation.ahead.y, orientation.ahead.z);
        pan.direction = new SteamAudio.Vector(direction.dot(right), direction.dot(up), -direction.dot(ahead));
        if (!BinauralSpatializer.direct(this, pan.direction, dry, dryStereo))
            api.iplPanningEffectApply(panning.getValue(), pan, dry, dryStereo);
        for (int c = 0; c < 4; c++) wet.memory(c).clear();
        if (!awaitingReflections && impulse != null && impulse.ir != null) {
            if (tail) api.iplReflectionEffectGetTail(reflections.getValue(), wet, null);
            else api.iplReflectionEffectApply(reflections.getValue(), impulse, input, wet, null);
        }
        // Non-finite wet (a NaN/Inf IR, whatever its source) must never reach the mix: it would
        // poison the limiter and silence the dry path too. Suppress and reset the effect.
        boolean poisoned = false;
        for (int c = 0; c < 4 && !poisoned; c++) {
            float[] channel = wet.memory(c).getFloatArray(0, FRAME);
            for (float sample : channel) {
                if (!Float.isFinite(sample)) { poisoned = true; break; }
            }
        }
        if (poisoned) {
            for (int c = 0; c < 4; c++) wet.memory(c).clear();
            resetReflections();
            DimBlendRadio.LOGGER.warn("[radio] non-finite reflection field #{}; requesting fresh simulation", ++invalidFields);
        }
        var decode = new SteamAudio.DecodeParams();
        decode.orientation = orientation;
        if (meter) {
            double wetPre = 0;
            for (int c = 0; c < 4; c++) {
                float[] channel = wet.memory(c).getFloatArray(0, FRAME);
                for (float sample : channel) wetPre += sample * (double) sample;
            }
            WET_PRE_DECODE.add(wetPre);
        }
        if (!BinauralSpatializer.reflections(this, orientation, wet, wetStereo))
            api.iplAmbisonicsDecodeEffectApply(decoding.getValue(), decode, wet, wetStereo);
        float[][] output = new float[2][FRAME];
        float peak = 0;
        double wetEnergy = 0, dryEnergy = 0;
        for (int c = 0; c < 2; c++) {
            float[] d = dryStereo.memory(c).getFloatArray(0, FRAME);
            float[] w = wetStereo.memory(c).getFloatArray(0, FRAME);
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
        // Reflective rooms can legitimately sum past full scale; a fast-attack block limiter
        // with ~1s release keeps the mix intact instead of hard-clipping at the PCM write.
        float target = peak > 0.95f ? 0.95f / peak : 1;
        float release = (float) (1 - Math.exp(-FRAME / (double) rate));
        limiterGain = target < limiterGain ? target : limiterGain + (target - limiterGain) * release;
        if (limiterGain < 1) {
            for (int c = 0; c < 2; c++) for (int i = 0; i < FRAME; i++) output[c][i] *= limiterGain;
        }
        return output;
    }

    public int tailSamples() { return api.iplReflectionEffectGetTailSize(reflections.getValue()); }

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
