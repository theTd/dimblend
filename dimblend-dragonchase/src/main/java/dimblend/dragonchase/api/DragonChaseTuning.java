package dimblend.dragonchase.api;

import dimblend.dragonchase.Config;
import java.util.Locale;

/**
 * Server-thread entry point for the eight Dragon Chase tuning values, used by this mod's own rules
 * and by optional configuration panels in other mods (dimblend-experience's tuning screen).
 *
 * <p>Defaults, ranges and steps are the locked values of requirements.md §3.1; the damage cap range
 * 0.5-10 is the plan's proposal.
 */
public final class DragonChaseTuning {
    /** One tuning value. {@code checkbox} options store 0 or 1. */
    public enum Option {
        SPEED_FLOOR_ENABLED("speed_floor_enabled", 1.0D, 0.0D, 1.0D, 1.0D, true),
        GENERATION_SPEED_STEP("generation_speed_step", 1.0D, 0.0D, 8.0D, 0.5D, false),
        HEALTH_MULTIPLIER("health_multiplier", 1.0D, 0.2D, 5.0D, 0.2D, false),
        LOSE_DISTANCE("lose_distance", 512.0D, 256.0D, 1024.0D, 16.0D, false),
        MIN_SPEED_GROWTH_DISTANCE("min_speed_growth_distance", 512.0D, 16.0D, 2048.0D, 16.0D, false),
        MIN_SPEED_CAP("min_speed_cap", 32.0D, 16.0D, 64.0D, 1.0D, false),
        FIREBALL_DAMAGE_THRESHOLD("fireball_damage_threshold", 32.0D, 4.0D, 64.0D, 4.0D, false),
        DAMAGE_CAP_PERCENT("damage_cap_percent", 2.0D, 0.5D, 10.0D, 0.5D, false);

        private final String id;
        private final double defaultValue;
        private final double minimum;
        private final double maximum;
        private final double step;
        private final boolean checkbox;

        Option(String id, double defaultValue, double minimum, double maximum, double step, boolean checkbox) {
            this.id = id;
            this.defaultValue = defaultValue;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.checkbox = checkbox;
        }

        public String id() { return id; }
        public double defaultValue() { return defaultValue; }
        public double minimum() { return minimum; }
        public double maximum() { return maximum; }
        public double step() { return step; }
        public boolean checkbox() { return checkbox; }

        public boolean isValid(double value) {
            if (!Double.isFinite(value) || value < minimum || value > maximum) {
                return false;
            }
            if (checkbox) {
                return value == 0.0D || value == 1.0D;
            }
            double scaled = (value - minimum) / step;
            return Math.abs(scaled - Math.rint(scaled)) < 1.0E-7D;
        }

        public String format(double value) {
            int decimals = step >= 1.0D ? 0 : step >= 0.1D ? 1 : 2;
            return String.format(Locale.ROOT, "%." + decimals + "f", value);
        }
    }

    public static boolean isLoaded() {
        return Config.SPEC.isLoaded();
    }

    /** Current value, or the default before the server config is loaded. */
    public static double get(Option option) {
        return isLoaded() ? Config.value(option) : option.defaultValue();
    }

    public static void set(Option option, double value) {
        if (!option.isValid(value)) {
            throw new IllegalArgumentException(option.id() + " out of range or off-step: " + value);
        }
        Config.setValue(option, value);
    }

    public static boolean speedFloorEnabled() { return get(Option.SPEED_FLOOR_ENABLED) >= 0.5D; }
    public static double generationSpeedStep() { return get(Option.GENERATION_SPEED_STEP); }
    public static double healthMultiplier() { return get(Option.HEALTH_MULTIPLIER); }
    public static double loseDistance() { return get(Option.LOSE_DISTANCE); }
    public static double minSpeedGrowthDistance() { return get(Option.MIN_SPEED_GROWTH_DISTANCE); }
    public static double minSpeedCap() { return get(Option.MIN_SPEED_CAP); }
    public static double fireballDamageThreshold() { return get(Option.FIREBALL_DAMAGE_THRESHOLD); }
    public static double damageCapPercent() { return get(Option.DAMAGE_CAP_PERCENT); }

    private DragonChaseTuning() {
    }
}
