package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;

/**
 * The enderman and shulker ambient clips End reception lets through, as mono PCM. Current resource
 * pack variants are decoded once in the background and resampled per playback sample rate on first
 * use; a sound reload drops them. Each sound event keeps its 7:1 group weight regardless of how
 * many variants it has.
 */
public final class RadioEndVoices {
    static final int ENDERMAN_WEIGHT = 7;
    static final int SHULKER_WEIGHT = 1;

    private record Clip(float[] samples, float rate) { }
    private record VoiceEvent(ResourceLocation id, int weight) { }
    private record VoiceSource(int weight, List<ResourceLocation> paths) { }
    private record VoiceGroup(int weight, List<Clip> clips) { }

    /** A sound event's resampled variants and its exact integer group weight. */
    record WeightedClips(int weight, List<float[]> clips) {
        WeightedClips {
            if (weight <= 0 || clips == null || clips.isEmpty()) {
                throw new IllegalArgumentException("A voice group needs a positive weight and non-empty clips");
            }
            for (float[] clip : clips) {
                if (clip == null || clip.length == 0) {
                    throw new IllegalArgumentException("Voice clips must not be empty");
                }
            }
            clips = List.copyOf(clips);
        }
    }

    private static final Object LOCK = new Object();
    /** Decoded groups; null until loaded. */
    private static volatile List<VoiceGroup> groups;
    private static final Map<Integer, List<WeightedClips>> RESAMPLED = new ConcurrentHashMap<>();
    private static boolean loading;
    /** Bumped by {@link #clear}: a load started before it is discarded. */
    private static int generation;

    /** Client thread: starts loading the clips unless they are loaded or loading. */
    public static void prepare(Minecraft mc) {
        int started;
        synchronized (LOCK) {
            if (groups != null || loading) return;
            loading = true;
            started = generation;
        }
        List<VoiceSource> sources = new ArrayList<>();
        for (VoiceEvent voiceEvent : events()) {
            Set<ResourceLocation> paths = new LinkedHashSet<>();
            WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(voiceEvent.id());
            if (event != null) {
                // Variants are only reachable by drawing them; enough draws find every one.
                RandomSource random = RandomSource.create(0);
                for (int draw = 0; draw < 64; draw++) {
                    Sound sound = event.getSound(random);
                    if (sound != SoundManager.EMPTY_SOUND) paths.add(sound.getPath());
                }
            }
            sources.add(new VoiceSource(voiceEvent.weight(), List.copyOf(paths)));
        }
        ResourceManager resources = mc.getResourceManager();
        Util.backgroundExecutor().execute(() -> {
            List<VoiceGroup> decoded = new ArrayList<>();
            for (VoiceSource source : sources) {
                List<Clip> eventClips = new ArrayList<>();
                for (ResourceLocation path : source.paths()) {
                    try (InputStream input = resources.open(path); JOrbisAudioStream stream = new JOrbisAudioStream(input)) {
                        Clip clip = mono(stream.readAll(), stream.getFormat());
                        if (clip.samples().length > 0) eventClips.add(clip);
                    } catch (Exception e) {
                        DimBlendRadio.LOGGER.warn("[radio] could not load End reception voice {}", path, e);
                    }
                }
                if (!eventClips.isEmpty()) {
                    decoded.add(new VoiceGroup(source.weight(), List.copyOf(eventClips)));
                }
            }
            synchronized (LOCK) {
                if (generation != started) return;
                loading = false;
                groups = List.copyOf(decoded);
                RESAMPLED.clear();
            }
        });
    }

    private static List<VoiceEvent> events() {
        return List.of(
                new VoiceEvent(SoundEvents.ENDERMAN_AMBIENT.getLocation(), ENDERMAN_WEIGHT),
                new VoiceEvent(SoundEvents.SHULKER_AMBIENT.getLocation(), SHULKER_WEIGHT));
    }

    /** Audio thread: the weighted groups at {@code rate}, or none while they are not loaded. */
    static List<WeightedClips> clips(int rate) {
        List<VoiceGroup> loaded = groups;
        if (loaded == null) return List.of();
        return RESAMPLED.computeIfAbsent(rate, target -> loaded.stream()
                .map(group -> new WeightedClips(group.weight(), group.clips().stream()
                        .map(clip -> resample(clip, target)).toList())).toList());
    }

    /** Sound reload: resources may have changed. */
    public static void clear() {
        synchronized (LOCK) {
            generation++;
            loading = false;
            groups = null;
            RESAMPLED.clear();
        }
    }

    private static Clip mono(ByteBuffer pcm, AudioFormat format) {
        ByteBuffer samples = pcm.order(ByteOrder.nativeOrder());
        int channels = format.getChannels();
        int frames = samples.remaining() / 2 / channels;
        float[] mono = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += samples.getShort(samples.position() + (frame * channels + channel) * 2) / 32768f;
            }
            mono[frame] = sum / channels;
        }
        return new Clip(prepareClip(mono, format.getSampleRate()), format.getSampleRate());
    }

    /** Filter once off the audio thread, then level quiet variants without amplifying near-silence. */
    static float[] prepareClip(float[] samples, float rate) {
        float highPass = (float) (1.0 / (1.0 + 2 * Math.PI * 350 / rate));
        float lowPass = (float) (1.0 / (1.0 + rate / (2 * Math.PI * 4500)));
        float previous = 0, high = 0, low = 0, peak = 0;
        double energy = 0;
        float[] filtered = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            high = highPass * (high + samples[i] - previous);
            previous = samples[i];
            low += (high - low) * lowPass;
            filtered[i] = low;
            peak = Math.max(peak, Math.abs(low));
            energy += low * (double) low;
        }
        if (peak > 1.0e-4f) {
            double rms = Math.sqrt(energy / samples.length);
            float gain = (float) Math.min(8, Math.min(0.18 / rms, 0.85 / peak));
            for (int i = 0; i < filtered.length; i++) filtered[i] *= gain;
        }
        return filtered;
    }

    /** Linear interpolation: the clips are faint and band-limited afterwards. */
    static float[] resample(float[] samples, float from, int to) {
        if (samples.length == 0 || Math.round(from) == to) return samples;
        int length = Math.max(1, (int) ((long) samples.length * to / from));
        float[] out = new float[length];
        double step = from / to;
        for (int i = 0; i < length; i++) {
            double at = i * step;
            int index = (int) at;
            float next = index + 1 < samples.length ? samples[index + 1] : samples[samples.length - 1];
            float frac = (float) (at - index);
            out[i] = samples[Math.min(index, samples.length - 1)] * (1 - frac) + next * frac;
        }
        return out;
    }

    private static float[] resample(Clip clip, int rate) {
        return resample(clip.samples(), clip.rate(), rate);
    }

    private RadioEndVoices() {
    }
}
