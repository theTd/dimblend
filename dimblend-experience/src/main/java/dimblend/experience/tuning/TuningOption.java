package dimblend.experience.tuning;

import java.util.Locale;

/** Wire identifiers and validation are shared by the panel and server. */
public enum TuningOption {
    SHAKE_INTERVAL("shake_interval", 1.0D, 0.2D, 32.0D, 1, "simurail", Section.MAIN),
    SHAKE_FORCE("shake_force", 100.0D, 1000.0D, 0, "simurail"),
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
    // 默认值镜像 Config.SABLE_VOID_FIT_SCAN_BUDGET / SABLE_LAVA_MELT_SCAN_BUDGET（2），由 TuningOptionTest 钉死
    SABLE_VOID_FIT_SCAN_BUDGET("sable_void_fit_scan_budget", 2.0D, 1.0D, 64.0D, 0, "sable", Section.MAIN),
    SABLE_LAVA_MELT_SCAN_BUDGET("sable_lava_melt_scan_budget", 2.0D, 1.0D, 64.0D, 0, "sable", Section.MAIN),
    PORTABLE_ENGINE_LIMIT("portable_engine_limit", 1.0D, "simulated"),
    STEAM_OVERLOAD("steam_overload", 1.0D, "create"),
    DIESEL_OVERLOAD("diesel_overload", 1.0D, "createdieselgenerators"),
    MOTOR_OVERLOAD("motor_overload", 1.0D, "createaddition"),
    EXP_CLEAR_RATIO("exp_clear_ratio", 1.0D, 1.0D, 2, ""),
    // 默认值镜像 Config.ROTATING_MONSTER_DENSITY ×100（0.5→50%），由 TuningOptionTest 钉死；
    // 2026-10-26 起该百分比作用于旋转维度全部受控怪物生成来源（不止自然刷怪全局上限）
    ROTATING_MONSTER_CAP("rotating_monster_cap", 50.0D, 0.0D, 100.0D, 0, "", Section.MAIN),
    // 默认值镜像 Config.ITEM_DRAIN_GROWTH_COST_MB（20mb），由 TuningOptionTest 钉死
    ITEM_DRAIN_GROWTH_COST("item_drain_growth_cost", 20.0D, 0.0D, 1500.0D, 0, "create", Section.MAIN),
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
    SABLE_HEADLIGHT_OCCLUSION("sable_headlight_occlusion", 1.0D, "sable", Section.SABLE),
    SABLE_HEADLIGHT_LAVA_HIGHLIGHT("sable_headlight_lava_highlight", 1.0D, "sable", Section.SABLE),
    // 默认值镜像 Config.OBSERVER_BLAZE_RADIUS（32），由 ObserverBlazeRadiusTest 钉死
    OBSERVER_BLAZE_RADIUS("observer_blaze_radius", 32.0D, 16.0D, 64.0D, 0, "sable", Section.SABLE),
    // 默认值镜像 Config.STATION_CARRIAGE_LIMIT（6），由 TuningOptionTest 钉死；宽度 3-11、精度 1
    STATION_CARRIAGE_LIMIT("station_carriage_limit", 6.0D, 3.0D, 11.0D, 0, ""),
    // 末影龙追逐（requirements.md §3.1）：八项一一镜像 dimblend-dragonchase 的
    // api.DragonChaseTuning.Option，读写都经 DragonChaseTuningBridge 转交给那个 mod 的 server
    // config（默认值/范围/步进与那边逐项相同，由 DragonChaseTuning 自己再校验一次）。
    // 步进一律走显式 step 构造器：8 项里有 4 项的步进不是 10 的负幂（0.5 / 0.2 / 16 / 4）。
    DRAGON_CHASE_SPEED_FLOOR_ENABLED("dragon_chase_speed_floor_enabled", 1.0D, "dimblend_dragonchase",
            Section.DRAGON_CHASE),
    DRAGON_CHASE_GENERATION_SPEED_STEP("dragon_chase_generation_speed_step", 1.0D, 0.0D, 8.0D, 1,
            "dimblend_dragonchase", Section.DRAGON_CHASE, 0.5D),
    DRAGON_CHASE_HEALTH_MULTIPLIER("dragon_chase_health_multiplier", 1.0D, 0.2D, 5.0D, 1,
            "dimblend_dragonchase", Section.DRAGON_CHASE, 0.2D),
    DRAGON_CHASE_LOSE_DISTANCE("dragon_chase_lose_distance", 512.0D, 256.0D, 1024.0D, 0,
            "dimblend_dragonchase", Section.DRAGON_CHASE, 16.0D),
    DRAGON_CHASE_MIN_SPEED_GROWTH_DISTANCE("dragon_chase_min_speed_growth_distance", 512.0D, 16.0D, 2048.0D, 0,
            "dimblend_dragonchase", Section.DRAGON_CHASE, 16.0D),
    DRAGON_CHASE_MIN_SPEED_CAP("dragon_chase_min_speed_cap", 32.0D, 16.0D, 64.0D, 0,
            "dimblend_dragonchase", Section.DRAGON_CHASE, 1.0D),
    DRAGON_CHASE_FIREBALL_DAMAGE_THRESHOLD("dragon_chase_fireball_damage_threshold", 32.0D, 4.0D, 64.0D, 0,
            "dimblend_dragonchase", Section.DRAGON_CHASE, 4.0D),
    DRAGON_CHASE_DAMAGE_CAP_PERCENT("dragon_chase_damage_cap_percent", 2.0D, 0.5D, 10.0D, 1,
            "dimblend_dragonchase", Section.DRAGON_CHASE, 0.5D);

    /** Display pages of the tuning screen; the snapshot still carries every option. */
    public enum Section { MAIN, SABLE, DRAGON_CHASE }

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
    /** Headlights appear on the Sable page but belong to experience's server config. */
    public boolean headlight() {
        return this == SABLE_HEADLIGHT || this == SABLE_HEADLIGHT_RANGE
                || this == SABLE_HEADLIGHT_ANGLE || this == SABLE_HEADLIGHT_INTENSITY
                || this == SABLE_HEADLIGHT_FOG || this == SABLE_HEADLIGHT_FOG_DENSITY
                || this == SABLE_HEADLIGHT_FOG_SAMPLES || this == SABLE_HEADLIGHT_OCCLUSION
                || this == SABLE_HEADLIGHT_LAVA_HIGHLIGHT;
    }

    /** Options that appear on the Sable page but belong to experience's own server config. */
    public boolean experienceOwned() {
        return headlight() || this == OBSERVER_BLAZE_RADIUS;
    }

    /** Settings owned by sable's server config; page placement does not imply ownership. */
    public boolean sable() { return section == Section.SABLE && !experienceOwned(); }

    /**
     * Options of the Dragon Chase page: they live in {@code dimblend_dragonchase}'s server config
     * and are only routed through that mod's api, never stored here.
     */
    public boolean dragonChase() { return section == Section.DRAGON_CHASE; }

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
