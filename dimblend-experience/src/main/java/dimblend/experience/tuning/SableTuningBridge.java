package dimblend.experience.tuning;

import dev.ryanhcode.sable.SableServerConfig;
import dev.ryanhcode.sable.api.physics.SablePhysicsTuning;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Only loaded after the sable presence check. */
final class SableTuningBridge {
    static boolean isLoaded() {
        return SableServerConfig.SPEC.isLoaded();
    }

    static double get(TuningOption option) {
        return switch (option) {
            case SABLE_SUBSTEPS -> SableServerConfig.SUB_LEVEL_SUBSTEPS_PER_TICK.get();
            case SABLE_SOLVER_ITERATIONS -> SableServerConfig.SOLVER_ITERATIONS.get();
            case SABLE_PGS_ITERATIONS -> SableServerConfig.PGS_ITERATIONS.get();
            case SABLE_STABILIZATION_ITERATIONS -> SableServerConfig.STABILIZATION_ITERATIONS.get();
            case SABLE_PHYSICS_TICK_INTERVAL -> SableServerConfig.PHYSICS_TICK_INTERVAL.get();
            case SABLE_SLEEP_SKIP -> SableServerConfig.PHYSICS_SLEEP_SKIP.get() ? 1.0D : 0.0D;
            case SABLE_CONTACT_FORCE_EVENTS -> SableServerConfig.CONTACT_FORCE_EVENTS.get() ? 1.0D : 0.0D;
            case SABLE_BIOME_PROJECTION_FAST_PATH -> SableServerConfig.BIOME_PROJECTION_FAST_PATH.get() ? 1.0D : 0.0D;
            case SABLE_EMPTY_CONTAINER_FAST_PATH -> SableServerConfig.EMPTY_CONTAINER_FAST_PATH.get() ? 1.0D : 0.0D;
            case SHAKE_INTERVAL, SHAKE_FORCE, BRAKE_SIGNAL_CAP, BRAKE_SOUND_SPEED_THRESHOLD, DEADMAN_RADIUS, BOGIE_ROLL_TORQUE, SOILING_MULTIPLIER, COUPLER_REDSTONE,
                    COUPLER_SURVIVAL, LIMITED_WATER, COPPER_SINK_INFINITE, SABLE_VOID_FIT, SABLE_LAVA_MELT, SABLE_LAVA_MELT_SPEED,
                    PORTABLE_ENGINE_LIMIT, STEAM_OVERLOAD,
                    DIESEL_OVERLOAD, MOTOR_OVERLOAD, RADIO_STATIC, RADIO_END_VOICE_FREQUENCY, RADIO_END_VOICE_VOLUME, RADIO_ACOUSTIC_INTENSITY, EXP_CLEAR_RATIO ->
                    throw new IllegalArgumentException("Not a sable tuning option: " + option);
        };
    }

    /** Server thread: saves sable's config and re-applies it to every loaded level. */
    static void set(TuningOption option, double value) {
        boolean enabled = value == 1.0D;
        switch (option) {
            case SABLE_SUBSTEPS -> SableServerConfig.SUB_LEVEL_SUBSTEPS_PER_TICK.set((int) value);
            case SABLE_SOLVER_ITERATIONS -> SableServerConfig.SOLVER_ITERATIONS.set((int) value);
            case SABLE_PGS_ITERATIONS -> SableServerConfig.PGS_ITERATIONS.set((int) value);
            case SABLE_STABILIZATION_ITERATIONS -> SableServerConfig.STABILIZATION_ITERATIONS.set((int) value);
            case SABLE_PHYSICS_TICK_INTERVAL -> SableServerConfig.PHYSICS_TICK_INTERVAL.set((int) value);
            case SABLE_SLEEP_SKIP -> SableServerConfig.PHYSICS_SLEEP_SKIP.set(enabled);
            case SABLE_CONTACT_FORCE_EVENTS -> SableServerConfig.CONTACT_FORCE_EVENTS.set(enabled);
            case SABLE_BIOME_PROJECTION_FAST_PATH -> SableServerConfig.BIOME_PROJECTION_FAST_PATH.set(enabled);
            case SABLE_EMPTY_CONTAINER_FAST_PATH -> SableServerConfig.EMPTY_CONTAINER_FAST_PATH.set(enabled);
            case SHAKE_INTERVAL, SHAKE_FORCE, BRAKE_SIGNAL_CAP, BRAKE_SOUND_SPEED_THRESHOLD, DEADMAN_RADIUS, BOGIE_ROLL_TORQUE, SOILING_MULTIPLIER, COUPLER_REDSTONE,
                    COUPLER_SURVIVAL, LIMITED_WATER, SABLE_VOID_FIT, SABLE_LAVA_MELT, SABLE_LAVA_MELT_SPEED,
                    PORTABLE_ENGINE_LIMIT, STEAM_OVERLOAD,
                    DIESEL_OVERLOAD, MOTOR_OVERLOAD, RADIO_STATIC, RADIO_END_VOICE_FREQUENCY, RADIO_END_VOICE_VOLUME, RADIO_ACOUSTIC_INTENSITY, EXP_CLEAR_RATIO ->
                    throw new IllegalArgumentException("Not a sable tuning option: " + option);
        }
        SableServerConfig.SPEC.save();
        SablePhysicsTuning.applyToAllLevels(ServerLifecycleHooks.getCurrentServer());
    }

    private SableTuningBridge() {
    }
}
