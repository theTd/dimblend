package dimblend.radio.acoustics;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

/** Headphone localization for both direct sound and the simulated Ambisonic field. */
public final class BinauralSpatializer {
    public interface Api extends SteamAudio.Api {
        int iplHRTFCreate(Pointer context, SteamAudio.AudioSettings audio, HrtfSettings settings, PointerByReference hrtf);
        void iplHRTFRelease(PointerByReference hrtf);
        int iplBinauralEffectCreate(Pointer context, SteamAudio.AudioSettings audio, Settings settings, PointerByReference effect);
        int iplBinauralEffectApply(Pointer effect, Params params, SteamAudio.AudioBuffer input, SteamAudio.AudioBuffer output);
        void iplBinauralEffectRelease(PointerByReference effect);
    }
    @FieldOrder({"type", "file", "data", "bytes", "volume", "normalization"})
    public static class HrtfSettings extends Structure {
        public int type;
        public Pointer file, data;
        public int bytes;
        public float volume = 1;
        public int normalization;
    }
    @FieldOrder({"hrtf"})
    public static class Settings extends Structure { public Pointer hrtf; }
    @FieldOrder({"direction", "interpolation", "blend", "hrtf", "delays"})
    public static class Params extends Structure {
        public SteamAudio.Vector direction = new SteamAudio.Vector();
        public int interpolation = 1;
        public float blend = 1;
        public Pointer hrtf, delays;
    }
    private static final class State {
        final PointerByReference hrtf = new PointerByReference(), direct = new PointerByReference(), reflections = new PointerByReference();
        double propagationDelay = Double.NaN;
    }
    private static final Map<Object, State> STATES = new IdentityHashMap<>();
    private static Api api;

    public static synchronized void attach(Object owner, Pointer context, int rate) {
        if (STATES.containsKey(owner)) return;
        if (api == null) api = Native.load(Path.of(System.getProperty("java.io.tmpdir"),
                "dimblend-steamaudio-4.8.1", "phonon.dll").toString(), Api.class);
        var audio = new SteamAudio.AudioSettings();
        audio.samplingRate = rate;
        var state = new State();
        try {
            SteamAudio.check(api.iplHRTFCreate(context, audio, new HrtfSettings(), state.hrtf), "HRTF");
            var settings = new Settings();
            settings.hrtf = state.hrtf.getValue();
            SteamAudio.check(api.iplBinauralEffectCreate(context, audio, settings, state.direct), "binaural direct effect");
            var decode = new SteamAudio.DecodeSettings();
            decode.hrtf = state.hrtf.getValue();
            SteamAudio.check(api.iplAmbisonicsDecodeEffectCreate(context, audio, decode, state.reflections), "binaural reflection effect");
            STATES.put(owner, state);
        } catch (RuntimeException | Error error) { release(state); throw error; }
    }

    public static synchronized boolean direct(Object owner, SteamAudio.Vector direction,
            SteamAudio.AudioBuffer input, SteamAudio.AudioBuffer output) {
        State state = STATES.get(owner);
        if (state == null) return false;
        var params = new Params();
        params.direction = direction;
        params.hrtf = state.hrtf.getValue();
        api.iplBinauralEffectApply(state.direct.getValue(), params, input, output);
        return true;
    }

    public static synchronized boolean reflections(Object owner, SteamAudio.Space orientation,
            SteamAudio.AudioBuffer input, SteamAudio.AudioBuffer output) {
        State state = STATES.get(owner);
        if (state == null) return false;
        var params = new SteamAudio.DecodeParams();
        params.orientation = orientation;
        params.binaural = 1;
        params.hrtf = state.hrtf.getValue();
        api.iplAmbisonicsDecodeEffectApply(state.reflections.getValue(), params, input, output);
        return true;
    }

    public static synchronized void detach(Object owner) {
        State state = STATES.remove(owner);
        if (state != null) release(state);
    }

    public static synchronized void delay(Object owner, float[] line, int cursor, float[] samples, double target) {
        State state = STATES.get(owner);
        double previous = state == null || Double.isNaN(state.propagationDelay) ? target : state.propagationDelay;
        for (int i = 0; i < samples.length; i++) {
            line[cursor] = samples[i];
            double delay = previous + (target - previous) * (i + 1) / samples.length;
            double read = cursor - delay;
            int first = (int) Math.floor(read);
            double fraction = read - first;
            float a = line[Math.floorMod(first, line.length)];
            float b = line[Math.floorMod(first + 1, line.length)];
            samples[i] = (float) (a + (b - a) * fraction);
            cursor = (cursor + 1) % line.length;
        }
        if (state != null) state.propagationDelay = target;
    }
    private static void release(State state) {
        if (state.reflections.getValue() != null) api.iplAmbisonicsDecodeEffectRelease(state.reflections);
        if (state.direct.getValue() != null) api.iplBinauralEffectRelease(state.direct);
        if (state.hrtf.getValue() != null) api.iplHRTFRelease(state.hrtf);
    }
    private BinauralSpatializer() { }
}
