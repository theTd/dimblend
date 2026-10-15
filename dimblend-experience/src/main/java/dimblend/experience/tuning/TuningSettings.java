package dimblend.experience.tuning;

import dimblend.experience.Config;
import net.neoforged.fml.ModList;

public final class TuningSettings {
    public static boolean available(TuningOption option) {
        if (!Config.isLoaded() || (!option.modId().isEmpty() && !ModList.get().isLoaded(option.modId()))) {
            return false;
        }
        return switch (option) {
            case SOILING_MULTIPLIER -> CarwashTuningBridge.isLoaded();
            case BOGIE_ROLL_TORQUE -> SimurailTuningBridge.isLoaded();
            case RADIO_STATIC, RADIO_END_VOICE_FREQUENCY, RADIO_END_VOICE_VOLUME, RADIO_ACOUSTIC_INTENSITY -> RadioTuningBridge.isLoaded();
            case SABLE_SUBSTEPS, SABLE_SOLVER_ITERATIONS, SABLE_PGS_ITERATIONS, SABLE_STABILIZATION_ITERATIONS,
                    SABLE_PHYSICS_TICK_INTERVAL, SABLE_SLEEP_SKIP, SABLE_CONTACT_FORCE_EVENTS,
                    SABLE_BIOME_PROJECTION_FAST_PATH, SABLE_EMPTY_CONTAINER_FAST_PATH ->
                    SableTuningBridge.isLoaded();
            default -> true;
        };
    }

    public static double get(TuningOption option) {
        if (!Config.isLoaded()) {
            return option.defaultValue();
        }
        return switch (option) {
            case SHAKE_INTERVAL -> Config.TRAIN_SHAKE_INTERVAL_SECONDS.get();
            case SHAKE_FORCE -> Config.TRAIN_SHAKE_FORCE_PN.get();
            case BRAKE_SIGNAL_CAP -> Config.TRAIN_BRAKE_SIGNAL_CAP.get();
            case BRAKE_SOUND_SPEED_THRESHOLD -> Config.TRAIN_BRAKE_SOUND_SPEED_THRESHOLD.get();
            case DEADMAN_RADIUS -> Config.TRAIN_DEADMAN_RADIUS.get();
            case BOGIE_ROLL_TORQUE -> available(option) ? SimurailTuningBridge.get() : option.defaultValue();
            case SOILING_MULTIPLIER -> available(option) ? CarwashTuningBridge.get() : option.defaultValue();
            case RADIO_STATIC, RADIO_END_VOICE_FREQUENCY, RADIO_END_VOICE_VOLUME, RADIO_ACOUSTIC_INTENSITY -> available(option) ? RadioTuningBridge.get(option) : option.defaultValue();
            case COUPLER_REDSTONE -> Config.COUPLER_REDSTONE.get() ? 0.0D : 1.0D;
            case COUPLER_SURVIVAL -> Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.get() ? 1.0D : 0.0D;
            case LIMITED_WATER -> Config.LIMITED_WATER.get() ? 1.0D : 0.0D;
            case SABLE_VOID_FIT -> Config.SABLE_STRUCTURE_VOID_FIT.get() ? 1.0D : 0.0D;
            case SABLE_LAVA_MELT -> Config.SABLE_LAVA_MELT.get() ? 1.0D : 0.0D;
            case SABLE_LAVA_MELT_SPEED -> Config.SABLE_LAVA_MELT_SPEED.get();
            case PORTABLE_ENGINE_LIMIT -> Config.PORTABLE_ENGINE_EXCLUSIVITY.get() ? 1.0D : 0.0D;
            case STEAM_OVERLOAD -> Config.STEAM_ENGINE_OVERLOAD.get() ? 1.0D : 0.0D;
            case DIESEL_OVERLOAD -> Config.DIESEL_ENGINE_OVERLOAD.get() ? 1.0D : 0.0D;
            case MOTOR_OVERLOAD -> Config.ELECTRIC_MOTOR_OVERLOAD.get() ? 1.0D : 0.0D;
            case EXP_CLEAR_RATIO -> Config.DEATH_EXP_CLEAR_RATIO.get();
            case SABLE_SUBSTEPS, SABLE_SOLVER_ITERATIONS, SABLE_PGS_ITERATIONS, SABLE_STABILIZATION_ITERATIONS,
                    SABLE_PHYSICS_TICK_INTERVAL, SABLE_SLEEP_SKIP, SABLE_CONTACT_FORCE_EVENTS,
                    SABLE_BIOME_PROJECTION_FAST_PATH, SABLE_EMPTY_CONTAINER_FAST_PATH ->
                    available(option) ? SableTuningBridge.get(option) : option.defaultValue();
        };
    }

    public static void set(TuningOption option, double value) {
        if (!option.isValid(value) || !available(option)) {
            throw new IllegalArgumentException("Invalid or unavailable tuning option");
        }
        if (option == TuningOption.SOILING_MULTIPLIER) {
            CarwashTuningBridge.set(value);
        } else if (option == TuningOption.BOGIE_ROLL_TORQUE) {
            SimurailTuningBridge.set(value);
        } else if (option.radio()) {
            RadioTuningBridge.set(option, value);
        } else if (option.sable()) {
            SableTuningBridge.set(option, value);
        } else {
            applyExperience(option, value);
            Config.save();
        }
    }

    // Client updates only touch the synchronized in-memory config, never disk.
    public static void applyExperience(TuningOption option, double value) {
        boolean enabled = value == 1.0D;
        switch (option) {
            case SHAKE_INTERVAL -> Config.TRAIN_SHAKE_INTERVAL_SECONDS.set(value);
            case SHAKE_FORCE -> Config.TRAIN_SHAKE_FORCE_PN.set((int) value);
            case BRAKE_SIGNAL_CAP -> Config.TRAIN_BRAKE_SIGNAL_CAP.set((int) value);
            case BRAKE_SOUND_SPEED_THRESHOLD -> Config.TRAIN_BRAKE_SOUND_SPEED_THRESHOLD.set(value);
            case DEADMAN_RADIUS -> Config.TRAIN_DEADMAN_RADIUS.set(value);
            case COUPLER_REDSTONE -> Config.COUPLER_REDSTONE.set(!enabled);
            case COUPLER_SURVIVAL -> Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.set(enabled);
            case LIMITED_WATER -> Config.LIMITED_WATER.set(enabled);
            case SABLE_VOID_FIT -> Config.SABLE_STRUCTURE_VOID_FIT.set(enabled);
            case SABLE_LAVA_MELT -> Config.SABLE_LAVA_MELT.set(enabled);
            case SABLE_LAVA_MELT_SPEED -> Config.SABLE_LAVA_MELT_SPEED.set(value);
            case PORTABLE_ENGINE_LIMIT -> Config.PORTABLE_ENGINE_EXCLUSIVITY.set(enabled);
            case STEAM_OVERLOAD -> Config.STEAM_ENGINE_OVERLOAD.set(enabled);
            case DIESEL_OVERLOAD -> Config.DIESEL_ENGINE_OVERLOAD.set(enabled);
            case MOTOR_OVERLOAD -> Config.ELECTRIC_MOTOR_OVERLOAD.set(enabled);
            case EXP_CLEAR_RATIO -> Config.DEATH_EXP_CLEAR_RATIO.set(value);
            case BOGIE_ROLL_TORQUE, SOILING_MULTIPLIER, RADIO_STATIC, RADIO_END_VOICE_FREQUENCY, RADIO_END_VOICE_VOLUME, RADIO_ACOUSTIC_INTENSITY -> { }
            case SABLE_SUBSTEPS, SABLE_SOLVER_ITERATIONS, SABLE_PGS_ITERATIONS, SABLE_STABILIZATION_ITERATIONS,
                    SABLE_PHYSICS_TICK_INTERVAL, SABLE_SLEEP_SKIP, SABLE_CONTACT_FORCE_EVENTS,
                    SABLE_BIOME_PROJECTION_FAST_PATH, SABLE_EMPTY_CONTAINER_FAST_PATH -> { }
        }
    }

    /**
     * A remote client's copy of the server's values, in memory only. Radio settings drive each
     * client's own audio, so they are applied where the server offers them and radio is installed.
     */
    public static void applyRemote(TuningOption option, double value, boolean serverAvailable) {
        if (option.radio()) {
            if (serverAvailable && ModList.get().isLoaded(option.modId())) {
                RadioTuningBridge.apply(option, value);
            }
        } else if (Config.isLoaded()) {
            applyExperience(option, value);
        }
    }

    private TuningSettings() {
    }
}
