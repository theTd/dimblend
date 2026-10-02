package dimblend.radio.acoustics;

import dimblend.radio.DimBlendRadio;
import java.util.Objects;

/**
 * A live-tunable float system property read on the audio thread: parsed once per distinct value;
 * a malformed or out-of-range value is logged once and the default is used instead of throwing.
 */
public final class AcousticTuningProperty {
    private record Parsed(String raw, float value) { }
    private final String name;
    private final float fallback, maximum;
    private volatile Parsed parsed;

    /** Accepts finite values in {@code [0, maximum]}. */
    public AcousticTuningProperty(String name, float fallback, float maximum) {
        this.name = name;
        this.fallback = fallback;
        this.maximum = maximum;
        parsed = new Parsed(null, fallback);
    }

    public float value() {
        String raw = System.getProperty(name);
        Parsed cached = parsed;
        if (Objects.equals(cached.raw(), raw)) return cached.value();
        float value = fallback;
        if (raw != null) {
            try { value = Float.parseFloat(raw.trim()); }
            catch (NumberFormatException invalid) { value = Float.NaN; }
            if (!Float.isFinite(value) || value < 0 || value > maximum) {
                DimBlendRadio.LOGGER.warn("[radio] ignoring invalid -D{}={}; using {}", name, raw, fallback);
                value = fallback;
            }
        }
        parsed = new Parsed(raw, value);
        return value;
    }
}
