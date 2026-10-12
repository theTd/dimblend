package dimblend.experience.client;

import dimblend.experience.tuning.perf.JvmPerfProbe;
import dimblend.experience.tuning.perf.PerfCheck;
import dimblend.experience.tuning.perf.PerfCheckResult;
import dimblend.experience.tuning.perf.PerfRules;
import dimblend.experience.tuning.perf.PerfScope;
import dimblend.experience.tuning.perf.PerfStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Checklist steps that describe this game client; fixes here change only local options. */
final class ClientPerfChecks {
    static List<PerfCheckResult> collect(Minecraft minecraft) {
        List<PerfCheckResult> results = new ArrayList<>();
        boolean singleplayer = minecraft.getSingleplayerServer() != null;
        if (!singleplayer) {
            // A remote client is its own JVM; in singleplayer the server results already cover it.
            results.addAll(JvmPerfProbe.collect(PerfScope.CLIENT));
        }
        if (singleplayer) {
            // The integrated server follows this option every tick (IntegratedServer#tickServer).
            int distance = minecraft.options.simulationDistance().get();
            results.add(distance(PerfCheck.SIMULATION_DISTANCE, distance, PerfRules.SIMULATION_DISTANCE_MAX));
        }
        results.add(distance(PerfCheck.RENDER_DISTANCE, minecraft.options.renderDistance().get(),
                PerfRules.RENDER_DISTANCE_MAX));
        results.add(sableInterpolation());
        return results;
    }

    /** Applies a client-side fix; returns false when the step has no fix on this side. */
    static boolean fix(Minecraft minecraft, PerfCheck check) {
        switch (check) {
            case SIMULATION_DISTANCE -> minecraft.options.simulationDistance().set(PerfRules.SIMULATION_DISTANCE_MAX);
            case RENDER_DISTANCE -> minecraft.options.renderDistance().set(PerfRules.RENDER_DISTANCE_MAX);
            case SABLE_INTERPOLATION -> {
                if (!sableLoaded()) {
                    return false;
                }
                SableClientTuningBridge.setInterpolationDelay(PerfRules.SABLE_INTERPOLATION_RECOMMENDED);
                return true;
            }
            default -> {
                return false;
            }
        }
        minecraft.options.save();
        return true;
    }

    private static PerfCheckResult distance(PerfCheck check, int current, int max) {
        boolean high = current > max;
        return PerfCheckResult.of(check, PerfScope.CLIENT, PerfRules.distanceStatus(current, max),
                high ? "high" : "ok", high, Integer.toString(current), Integer.toString(max));
    }

    private static PerfCheckResult sableInterpolation() {
        String recommended = String.format(Locale.ROOT, "%.1f", PerfRules.SABLE_INTERPOLATION_RECOMMENDED);
        if (!sableLoaded()) {
            return PerfCheckResult.of(PerfCheck.SABLE_INTERPOLATION, PerfScope.CLIENT, PerfStatus.UNAVAILABLE,
                    "missing", false, "-", recommended);
        }
        double delay = SableClientTuningBridge.interpolationDelay();
        PerfStatus status = PerfRules.interpolationStatus(delay);
        return PerfCheckResult.of(PerfCheck.SABLE_INTERPOLATION, PerfScope.CLIENT, status,
                status == PerfStatus.OK ? "ok" : "short", status != PerfStatus.OK,
                String.format(Locale.ROOT, "%.1f", delay), recommended);
    }

    private static boolean sableLoaded() {
        return ModList.get().isLoaded("sable") && SableClientTuningBridge.isLoaded();
    }

    private ClientPerfChecks() {
    }
}
