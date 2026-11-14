package dimblend.experience.tuning;

import dimblend.dragonchase.api.DragonChaseTuning;
import java.util.EnumMap;
import java.util.Map;

/**
 * Only loaded after the {@code dimblend_dragonchase} presence check ({@link TuningOption#modId()}).
 *
 * <p>Dragon Chase owns its eight server-config values; this bridge only routes the panel's options
 * to {@code dimblend.dragonchase.api.DragonChaseTuning} and never keeps a second copy. The panel's
 * ranges and steps match that mod's defaults (requirements.md §3.1) because the panel validates
 * first and the owning config validates again.
 *
 * <p>The one-to-one mapping is spelled out, so renaming an option on either side is a compile
 * error instead of a silent id drift.
 */
final class DragonChaseTuningBridge {
    private static final Map<TuningOption, DragonChaseTuning.Option> OPTIONS = new EnumMap<>(TuningOption.class);

    static {
        map(TuningOption.DRAGON_CHASE_SPEED_FLOOR_ENABLED, DragonChaseTuning.Option.SPEED_FLOOR_ENABLED);
        map(TuningOption.DRAGON_CHASE_GENERATION_SPEED_STEP, DragonChaseTuning.Option.GENERATION_SPEED_STEP);
        map(TuningOption.DRAGON_CHASE_HEALTH_MULTIPLIER, DragonChaseTuning.Option.HEALTH_MULTIPLIER);
        map(TuningOption.DRAGON_CHASE_LOSE_DISTANCE, DragonChaseTuning.Option.LOSE_DISTANCE);
        map(TuningOption.DRAGON_CHASE_MIN_SPEED_GROWTH_DISTANCE,
                DragonChaseTuning.Option.MIN_SPEED_GROWTH_DISTANCE);
        map(TuningOption.DRAGON_CHASE_MIN_SPEED_CAP, DragonChaseTuning.Option.MIN_SPEED_CAP);
        map(TuningOption.DRAGON_CHASE_FIREBALL_DAMAGE_THRESHOLD,
                DragonChaseTuning.Option.FIREBALL_DAMAGE_THRESHOLD);
        map(TuningOption.DRAGON_CHASE_DAMAGE_CAP_PERCENT, DragonChaseTuning.Option.DAMAGE_CAP_PERCENT);
    }

    static boolean isLoaded() {
        return DragonChaseTuning.isLoaded();
    }

    static double get(TuningOption option) {
        return DragonChaseTuning.get(target(option));
    }

    /** Server thread: the owning config saves itself. */
    static void set(TuningOption option, double value) {
        DragonChaseTuning.set(target(option), value);
    }

    private static DragonChaseTuning.Option target(TuningOption option) {
        DragonChaseTuning.Option target = OPTIONS.get(option);
        if (target == null) {
            throw new IllegalArgumentException(option.id() + " is not a Dragon Chase option");
        }
        return target;
    }

    private static void map(TuningOption option, DragonChaseTuning.Option target) {
        OPTIONS.put(option, target);
    }

    private DragonChaseTuningBridge() {
    }
}
