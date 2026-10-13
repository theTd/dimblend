package dimblend.experience.tuning;

import com.crystaelix.simurail.config.SimurailConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Only loaded after the simurail presence check. Simurail's own server config stays the only
 * source of truth: the bogie roll torque cap is its {@code bogeyAngularSpringMaxTorque}.
 */
final class SimurailTuningBridge {
    static boolean isLoaded() {
        ModConfigSpec specification = SimurailConfig.SERVER.specification;
        return specification != null && specification.isLoaded();
    }

    static double get() {
        return SimurailConfig.SERVER.physics.bogeyAngularSpringMaxTorque.get();
    }

    /**
     * Server thread. catnip's {@code CValue#set} also saves Simurail's config file, and Simurail
     * reads the cap on every physics step, so nothing else has to be refreshed.
     */
    static void set(double value) {
        SimurailConfig.SERVER.physics.bogeyAngularSpringMaxTorque.set(value);
    }

    private SimurailTuningBridge() {
    }
}
