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
            case RADIO_STATIC, RADIO_ACOUSTIC_INTENSITY -> RadioTuningBridge.isLoaded();
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
            case SOILING_MULTIPLIER -> available(option) ? CarwashTuningBridge.get() : option.defaultValue();
            case RADIO_STATIC, RADIO_ACOUSTIC_INTENSITY -> available(option) ? RadioTuningBridge.get(option) : option.defaultValue();
            case COUPLER_REDSTONE -> Config.COUPLER_REDSTONE.get() ? 0.0D : 1.0D;
            case COUPLER_SURVIVAL -> Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.get() ? 1.0D : 0.0D;
            case LIMITED_WATER -> Config.LIMITED_WATER.get() ? 1.0D : 0.0D;
            case PORTABLE_ENGINE_LIMIT -> Config.PORTABLE_ENGINE_EXCLUSIVITY.get() ? 1.0D : 0.0D;
            case STEAM_OVERLOAD -> Config.STEAM_ENGINE_OVERLOAD.get() ? 1.0D : 0.0D;
            case DIESEL_OVERLOAD -> Config.DIESEL_ENGINE_OVERLOAD.get() ? 1.0D : 0.0D;
            case MOTOR_OVERLOAD -> Config.ELECTRIC_MOTOR_OVERLOAD.get() ? 1.0D : 0.0D;
        };
    }

    public static void set(TuningOption option, double value) {
        if (!option.isValid(value) || !available(option)) {
            throw new IllegalArgumentException("Invalid or unavailable tuning option");
        }
        if (option == TuningOption.SOILING_MULTIPLIER) {
            CarwashTuningBridge.set(value);
        } else if (option.radio()) {
            RadioTuningBridge.set(option, value);
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
            case COUPLER_REDSTONE -> Config.COUPLER_REDSTONE.set(!enabled);
            case COUPLER_SURVIVAL -> Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.set(enabled);
            case LIMITED_WATER -> Config.LIMITED_WATER.set(enabled);
            case PORTABLE_ENGINE_LIMIT -> Config.PORTABLE_ENGINE_EXCLUSIVITY.set(enabled);
            case STEAM_OVERLOAD -> Config.STEAM_ENGINE_OVERLOAD.set(enabled);
            case DIESEL_OVERLOAD -> Config.DIESEL_ENGINE_OVERLOAD.set(enabled);
            case MOTOR_OVERLOAD -> Config.ELECTRIC_MOTOR_OVERLOAD.set(enabled);
            case SOILING_MULTIPLIER, RADIO_STATIC, RADIO_ACOUSTIC_INTENSITY -> { }
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
