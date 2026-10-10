package dimblend.experience.tuning;

import java.util.Locale;

/** Wire identifiers and validation are shared by the panel and server. */
public enum TuningOption {
    SHAKE_INTERVAL("shake_interval", 2.0D, 60.0D, 1, "simurail"),
    SHAKE_FORCE("shake_force", 1200.0D, 10000.0D, 0, "simurail"),
    SOILING_MULTIPLIER("soiling_multiplier", 0.5D, 1.0D, 2, "dimblend_carwash"),
    COUPLER_REDSTONE("coupler_redstone", 0.0D, "simurail"),
    COUPLER_SURVIVAL("coupler_survival", 0.0D, "simurail"),
    LIMITED_WATER("limited_water", 1.0D, ""),
    SABLE_VOID_FIT("sable_void_fit", 1.0D, "sable"),
    PORTABLE_ENGINE_LIMIT("portable_engine_limit", 1.0D, "simulated"),
    STEAM_OVERLOAD("steam_overload", 1.0D, "create"),
    DIESEL_OVERLOAD("diesel_overload", 1.0D, "createdieselgenerators"),
    MOTOR_OVERLOAD("motor_overload", 1.0D, "createaddition"),
    RADIO_STATIC("radio_static", 0.5D, 2.0D, 2, "dimblend_radio"),
    RADIO_ACOUSTIC_INTENSITY("radio_acoustic_intensity", 1.0D, 2.0D, 2, "dimblend_radio"),
    EXP_CLEAR_RATIO("exp_clear_ratio", 1.0D, 1.0D, 2, "");

    public static final String PREFIX = "screen.dimblend_experience.tuning.";
    private final String id;
    private final double defaultValue;
    private final double maximum;
    private final int decimals;
    private final String modId;

    TuningOption(String id, double defaultValue, String modId) {
        this(id, defaultValue, 1.0D, -1, modId);
    }

    TuningOption(String id, double defaultValue, double maximum, int decimals, String modId) {
        this.id = id;
        this.defaultValue = defaultValue;
        this.maximum = maximum;
        this.decimals = decimals;
        this.modId = modId;
    }

    public String id() { return id; }
    public String key() { return PREFIX + id; }
    public String modId() { return modId; }
    public double defaultValue() { return defaultValue; }
    public boolean isCheckbox() { return decimals < 0; }
    public double step() { return Math.pow(10.0D, -Math.max(0, decimals)); }
    public double maximum() { return maximum; }
    /** Settings owned by dimblend_radio, which every client applies to its own audio. */
    public boolean radio() { return this == RADIO_STATIC || this == RADIO_ACOUSTIC_INTENSITY; }

    public boolean isValid(double value) {
        if (!Double.isFinite(value) || value < 0.0D || value > maximum) {
            return false;
        }
        if (isCheckbox()) {
            return value == 0.0D || value == 1.0D;
        }
        if (value > 0.0D && value < step()) {
            return false;
        }
        double scaled = value / step();
        return Math.abs(scaled - Math.rint(scaled)) < 1.0E-7D;
    }

    public String format(double value) {
        return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", value);
    }

    public static TuningOption byId(String id) {
        for (TuningOption option : values()) {
            if (option.id.equals(id)) {
                return option;
            }
        }
        return null;
    }
}
