package dimblend.radio.acoustics;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.PointerByReference;
import java.util.IdentityHashMap;
import java.util.Map;

/** Headphone localization for both direct sound and the simulated Ambisonic field. */
public final class BinauralSpatializer {
    public interface Api extends SteamAudio.Api {
        int iplHRTFCreate(Pointer context, SteamAudio.AudioSettings audio, HrtfSettings settings, PointerByReference hrtf);
        void iplHRTFRelease(PointerByReference hrtf);
        int iplBinauralEffectCreate(Pointer context, SteamAudio.AudioSettings audio, Settings settings, PointerByReference effect);
        int iplBinauralEffectApply(Pointer effect, Params params, SteamAudio.AudioBuffer input, SteamAudio.AudioBuffer output);
        void iplBinauralEffectReset(Pointer effect);
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
    // Params are reused per owner (each owner renders on one thread at a time); values are copied
    // in, never assigned as embedded Structures.
    private static final class State {
        final PointerByReference hrtf = new PointerByReference(), direct = new PointerByReference(), reflections = new PointerByReference();
        final Params directParams = new Params();
        final SteamAudio.DecodeParams decodeParams = new SteamAudio.DecodeParams();
    }
    private static final Map<Object, State> STATES = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Object HRTF_CREATION = new Object();
    private static volatile Api api;

    // The caller serializes each owner's lifetime. HRTF creation needs its own SDK-wide lock,
    // but must never hold the map lock (or a lock used by other owners' audio processing).
    public static void attach(Object owner, Pointer context, int rate) {
        if (STATES.containsKey(owner)) return;
        var audio = new SteamAudio.AudioSettings();
        audio.samplingRate = rate;
        var state = new State();
        try {
            synchronized (HRTF_CREATION) {
                if (api == null) api = Native.load(SteamAudio.library().toString(), Api.class);
                SteamAudio.check(api.iplHRTFCreate(context, audio, new HrtfSettings(), state.hrtf), "HRTF");
            }
            var settings = new Settings();
            settings.hrtf = state.hrtf.getValue();
            SteamAudio.check(api.iplBinauralEffectCreate(context, audio, settings, state.direct), "binaural direct effect");
            var decode = new SteamAudio.DecodeSettings();
            decode.hrtf = state.hrtf.getValue();
            SteamAudio.check(api.iplAmbisonicsDecodeEffectCreate(context, audio, decode, state.reflections), "binaural reflection effect");
            state.directParams.hrtf = state.hrtf.getValue();
            state.decodeParams.binaural = 1;
            state.decodeParams.hrtf = state.hrtf.getValue();
            STATES.put(owner, state);
        } catch (RuntimeException | Error error) { release(state); throw error; }
    }

    public static boolean direct(Object owner, SteamAudio.Vector direction,
            SteamAudio.AudioBuffer input, SteamAudio.AudioBuffer output) {
        State state = STATES.get(owner);
        if (state == null) return false;
        state.directParams.direction.set(direction);
        api.iplBinauralEffectApply(state.direct.getValue(), state.directParams, input, output);
        return true;
    }

    public static boolean reflections(Object owner, SteamAudio.Space orientation,
            SteamAudio.AudioBuffer input, SteamAudio.AudioBuffer output) {
        State state = STATES.get(owner);
        if (state == null) return false;
        state.decodeParams.orientation.set(orientation);
        api.iplAmbisonicsDecodeEffectApply(state.reflections.getValue(), state.decodeParams, input, output);
        return true;
    }

    public static void detach(Object owner) {
        State state = STATES.remove(owner);
        if (state != null) release(state);
    }

    /** Forgets binaural filter history, e.g. after the effects sat idle. */
    public static void reset(Object owner) {
        State state = STATES.get(owner);
        if (state == null) return;
        api.iplBinauralEffectReset(state.direct.getValue());
        api.iplAmbisonicsDecodeEffectReset(state.reflections.getValue());
    }

    private static void release(State state) {
        if (state.reflections.getValue() != null) api.iplAmbisonicsDecodeEffectRelease(state.reflections);
        if (state.direct.getValue() != null) api.iplBinauralEffectRelease(state.direct);
        if (state.hrtf.getValue() != null) api.iplHRTFRelease(state.hrtf);
    }
    private BinauralSpatializer() { }
}
