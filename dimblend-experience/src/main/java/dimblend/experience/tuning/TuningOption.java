package dimblend.experience.tuning;

import java.util.Locale;

/** Wire identifiers and validation are shared by the panel and server. */
public enum TuningOption {
    SHAKE_INTERVAL("shake_interval", 2.0D, 60.0D, 1, "simurail"),
    SHAKE_FORCE("shake_force", 1200.0D, 10000.0D, 0, "simurail"),
    BRAKE_SIGNAL_CAP("brake_signal_cap", 1.0D, 1.0D, 15.0D, 0, "simurail", Section.MAIN),
    BRAKE_SOUND_SPEED_THRESHOLD("brake_sound_speed_threshold", 2.0D, 0.0D, 64.0D, 1, "simurail", Section.MAIN),
    DEADMAN_RADIUS("deadman_radius", 32.0D, 8.0D, 128.0D, 0, "simurail", Section.MAIN),
    BOGIE_ROLL_TORQUE("bogie_roll_torque", 5000.0D, 0.0D, 10000000.0D, 0, "simurail", Section.MAIN),
    SOILING_MULTIPLIER("soiling_multiplier", 0.5D, 1.0D, 2, "dimblend_carwash"),
    COUPLER_REDSTONE("coupler_redstone", 0.0D, "simurail"),
    COUPLER_SURVIVAL("coupler_survival", 0.0D, "simurail"),
    LIMITED_WATER("limited_water", 1.0D, ""),
    SABLE_VOID_FIT("sable_void_fit", 1.0D, "sable"),
    SABLE_LAVA_MELT("sable_lava_melt", 1.0D, "sable"),
    // 倍率默认值与 LavaMeltMath.DEFAULT_SPEED_MULTIPLIER 的镜像由 TuningOptionTest 钉死
    SABLE_LAVA_MELT_SPEED("sable_lava_melt_speed", 4.0D, 0.5D, 64.0D, 1, "sable", Section.MAIN),
    PORTABLE_ENGINE_LIMIT("portable_engine_limit", 1.0D, "simulated"),
    STEAM_OVERLOAD("steam_overload", 1.0D, "create"),
    DIESEL_OVERLOAD("diesel_overload", 1.0D, "createdieselgenerators"),
    MOTOR_OVERLOAD("motor_overload", 1.0D, "createaddition"),
    RADIO_STATIC("radio_static", 0.5D, 2.0D, 2, "dimblend_radio"),
    RADIO_END_VOICE_FREQUENCY("radio_end_voice_frequency", 2.0D, 4.0D, 1, "dimblend_radio"),
    // 默认值镜像 dimblend.radio.RadioLiveSettings.DEFAULT_END_VOICE_VOLUME（旧硬编码 0.5 × 1.5），由 TuningOptionTest 钉死
    RADIO_END_VOICE_VOLUME("radio_end_voice_volume", 0.75D, 2.0D, 2, "dimblend_radio"),
    RADIO_ACOUSTIC_INTENSITY("radio_acoustic_intensity", 1.0D, 2.0D, 2, "dimblend_radio"),
    EXP_CLEAR_RATIO("exp_clear_ratio", 1.0D, 1.0D, 2, ""),
    SABLE_SUBSTEPS("sable_substeps", 2.0D, 1.0D, 10.0D, 0, "sable", Section.SABLE),
    SABLE_SOLVER_ITERATIONS("sable_solver_iterations", 18.0D, 1.0D, 64.0D, 0, "sable", Section.SABLE),
    SABLE_PGS_ITERATIONS("sable_pgs_iterations", 2.0D, 1.0D, 8.0D, 0, "sable", Section.SABLE),
    SABLE_STABILIZATION_ITERATIONS("sable_stabilization_iterations", 2.0D, 1.0D, 8.0D, 0, "sable", Section.SABLE),
    SABLE_PHYSICS_TICK_INTERVAL("sable_physics_tick_interval", 1.0D, 1.0D, 10.0D, 0, "sable", Section.SABLE),
    SABLE_SLEEP_SKIP("sable_sleep_skip", 1.0D, "sable", Section.SABLE),
    SABLE_CONTACT_FORCE_EVENTS("sable_contact_force_events", 1.0D, "sable", Section.SABLE),
    SABLE_BIOME_PROJECTION_FAST_PATH("sable_biome_projection_fast_path", 1.0D, "sable", Section.SABLE),
    SABLE_EMPTY_CONTAINER_FAST_PATH("sable_empty_container_fast_path", 1.0D, "sable", Section.SABLE),
    COPPER_SINK_INFINITE("copper_sink_infinite", 0.0D, "fluid"),
    SABLE_HEADLIGHT("sable_headlight", 1.0D, "sable", Section.SABLE),
    SABLE_HEADLIGHT_RANGE("sable_headlight_range", 24.0D, 4.0D, 64.0D, 1, "sable", Section.SABLE, 0.5D),
    SABLE_HEADLIGHT_ANGLE("sable_headlight_angle", 50.0D, 10.0D, 120.0D, 0, "sable", Section.SABLE),
    SABLE_HEADLIGHT_INTENSITY("sable_headlight_intensity", 1.0D, 0.0D, 3.0D, 2, "sable", Section.SABLE, 0.05D),
    SABLE_HEADLIGHT_FOG("sable_headlight_fog", 1.0D, "sable", Section.SABLE),
    SABLE_HEADLIGHT_FOG_DENSITY("sable_headlight_fog_density", 0.35D, 0.0D, 2.0D, 2, "sable", Section.SABLE),
    SABLE_HEADLIGHT_FOG_SAMPLES("sable_headlight_fog_samples", 8.0D, 4.0D, 24.0D, 0, "sable", Section.SABLE),
    SABLE_HEADLIGHT_OCCLUSION("sable_headlight_occlusion", 1.0D, "sable", Section.SABLE);

    /** Display pages of the tuning screen; the snapshot still carries every option. */
    public enum Section { MAIN, SABLE }

    public static final String PREFIX = "screen.dimblend_experience.tuning.";
    private final String id;
    private final double defaultValue;
    private final double minimum;
    private final double maximum;
    private final int decimals;
    private final String modId;
    private final Section section;
    private final double step;

    TuningOption(String id, double defaultValue, String modId) {
        this(id, defaultValue, 0.0D, 1.0D, -1, modId, Section.MAIN);
    }

    TuningOption(String id, double defaultValue, String modId, Section section) {
        this(id, defaultValue, 0.0D, 1.0D, -1, modId, section);
    }

    TuningOption(String id, double defaultValue, double maximum, int decimals, String modId) {
        this(id, defaultValue, 0.0D, maximum, decimals, modId, Section.MAIN);
    }

    TuningOption(String id, double defaultValue, double minimum, double maximum, int decimals, String modId) {
        this(id, defaultValue, minimum, maximum, decimals, modId, Section.MAIN);
    }

    TuningOption(String id, double defaultValue, double minimum, double maximum, int decimals, String modId,
                 Section section) {
        this(id, defaultValue, minimum, maximum, decimals, modId, section,
                Math.pow(10.0D, -Math.max(0, decimals)));
    }

    TuningOption(String id, double defaultValue, double minimum, double maximum, int decimals, String modId,
                 Section section, double step) {
        this.id = id;
        this.defaultValue = defaultValue;
        this.minimum = minimum;
        this.maximum = maximum;
        this.decimals = decimals;
        this.modId = modId;
        this.section = section;
        this.step = step;
    }

    public String id() { return id; }
    public String key() { return PREFIX + id; }
    public String modId() { return modId; }
    public Section section() { return section; }
    public double defaultValue() { return defaultValue; }
    public boolean isCheckbox() { return decimals < 0; }
    public double step() { return step; }
    public double minimum() { return minimum; }
    public double maximum() { return maximum; }
    /** Settings owned by dimblend_radio, which every client applies to its own audio. */
    public boolean radio() {
        return this == RADIO_STATIC || this == RADIO_END_VOICE_FREQUENCY || this == RADIO_END_VOICE_VOLUME
                || this == RADIO_ACOUSTIC_INTENSITY;
    }
    /** Headlights appear on the Sable page but belong to experience's server config. */
    public boolean headlight() {
        return this == SABLE_HEADLIGHT || this == SABLE_HEADLIGHT_RANGE
                || this == SABLE_HEADLIGHT_ANGLE || this == SABLE_HEADLIGHT_INTENSITY
                || this == SABLE_HEADLIGHT_FOG || this == SABLE_HEADLIGHT_FOG_DENSITY
                || this == SABLE_HEADLIGHT_FOG_SAMPLES || this == SABLE_HEADLIGHT_OCCLUSION;
    }

    /** Settings owned by sable's server config; page placement does not imply ownership. */
    public boolean sable() { return section == Section.SABLE && !headlight(); }

    public boolean isValid(double value) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
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
