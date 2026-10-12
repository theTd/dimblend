package dimblend.experience.tuning.perf;

import dimblend.experience.DimBlend;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSettings;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.fml.ModList;

/** Checklist steps that describe the server process (in singleplayer: the integrated server). */
public final class ServerPerfChecks {
    private static final List<TuningOption> SABLE_PHYSICS = List.of(TuningOption.SABLE_SUBSTEPS,
            TuningOption.SABLE_SOLVER_ITERATIONS, TuningOption.SABLE_PGS_ITERATIONS,
            TuningOption.SABLE_STABILIZATION_ITERATIONS, TuningOption.SABLE_PHYSICS_TICK_INTERVAL);
    /** Set once the fix wrote the spark config this run; spark only re-reads it on restart. */
    private static volatile boolean sparkDisabledThisRun;
    private static Boolean asyncSaveMixinActive;

    public static List<PerfCheckResult> collect(MinecraftServer server) {
        List<PerfCheckResult> results = new ArrayList<>();
        results.add(spark());
        results.addAll(JvmPerfProbe.collect(PerfScope.SERVER));
        results.add(asyncSave());
        results.add(finiteWater());
        results.add(overworldUnload(server));
        if (server.isDedicatedServer()) {
            // Singleplayer simulation distance is a client option; the client reports that step.
            int distance = server.getPlayerList().getSimulationDistance();
            boolean high = distance > PerfRules.SIMULATION_DISTANCE_MAX;
            results.add(PerfCheckResult.of(PerfCheck.SIMULATION_DISTANCE, PerfScope.SERVER,
                    PerfRules.distanceStatus(distance, PerfRules.SIMULATION_DISTANCE_MAX),
                    high ? "high_dedicated" : "ok", false,
                    Integer.toString(distance), Integer.toString(PerfRules.SIMULATION_DISTANCE_MAX)));
        }
        results.add(sablePhysics());
        return results;
    }

    /** Applies a server-side fix; returns false for steps whose fix is not on this side. */
    public static boolean fix(MinecraftServer server, PerfCheck check) throws IOException {
        switch (check) {
            case SPARK_BACKGROUND_PROFILER -> {
                SparkConfigFile.disableBackgroundProfiler();
                sparkDisabledThisRun = true;
                return true;
            }
            case OVERWORLD_UNLOAD -> {
                // The rule's change callback re-runs setDefaultSpawnPos, which drops the START
                // ticket right away; the overworld then unloads once nobody keeps it loaded.
                server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, server);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Whether the overworld can unload completely while nobody is in it. Vanilla already stops
     * ticking its entities and block entities 300 ticks after it empties (unless chunks are
     * force-loaded), but the spawn-chunk ticket keeps a 7x7 full area plus a wide partly
     * loaded ring in memory for the whole session.
     */
    private static PerfCheckResult overworldUnload(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        int radius = server.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        int forced = overworld.getForcedChunks().size();
        String[] args = {Integer.toString(radius), Integer.toString(forced),
                Integer.toString(overworld.getChunkSource().getLoadedChunksCount())};
        if (radius > 0) {
            return PerfCheckResult.of(PerfCheck.OVERWORLD_UNLOAD, PerfScope.SERVER, PerfStatus.WARN,
                    "spawn_chunks", true, args);
        }
        if (forced > 0) {
            return PerfCheckResult.of(PerfCheck.OVERWORLD_UNLOAD, PerfScope.SERVER, PerfStatus.WARN,
                    "forced", false, args);
        }
        return PerfCheckResult.of(PerfCheck.OVERWORLD_UNLOAD, PerfScope.SERVER, PerfStatus.OK, "ok", false, args);
    }

    private static PerfCheckResult spark() {
        if (!ModList.get().isLoaded("spark")) {
            return PerfCheckResult.of(PerfCheck.SPARK_BACKGROUND_PROFILER, PerfScope.SERVER, PerfStatus.OK,
                    "absent", false);
        }
        String config;
        try {
            config = SparkConfigFile.read();
        } catch (IOException e) {
            DimBlend.LOGGER.warn("Could not read {}", SparkConfigFile.path(), e);
            config = null;
        }
        if (PerfRules.sparkBackgroundEnabled(config)) {
            return PerfCheckResult.of(PerfCheck.SPARK_BACKGROUND_PROFILER, PerfScope.SERVER, PerfStatus.PROBLEM,
                    "on", true);
        }
        return sparkDisabledThisRun
                ? PerfCheckResult.of(PerfCheck.SPARK_BACKGROUND_PROFILER, PerfScope.SERVER, PerfStatus.WARN,
                        "restart", false)
                : PerfCheckResult.of(PerfCheck.SPARK_BACKGROUND_PROFILER, PerfScope.SERVER, PerfStatus.OK,
                        "off", false);
    }

    private static PerfCheckResult asyncSave() {
        if (!ModList.get().isLoaded("dimblend_asyncsave")) {
            return PerfCheckResult.of(PerfCheck.ASYNC_SAVE, PerfScope.SERVER, PerfStatus.WARN, "missing", false);
        }
        return asyncSaveMixinActive()
                ? PerfCheckResult.of(PerfCheck.ASYNC_SAVE, PerfScope.SERVER, PerfStatus.OK, "active", false)
                : PerfCheckResult.of(PerfCheck.ASYNC_SAVE, PerfScope.SERVER, PerfStatus.PROBLEM, "inactive", false);
    }

    /**
     * The mod can load without its mixins (2026-10-12: its mods.toml lacked the [[mixins]]
     * block, so every autosave write stayed on the server thread). Mixin handlers merged into
     * LevelStorageAccess carry the mod id in their names, which proves the config was applied.
     */
    private static synchronized boolean asyncSaveMixinActive() {
        if (asyncSaveMixinActive == null) {
            asyncSaveMixinActive = Arrays.stream(LevelStorageSource.LevelStorageAccess.class.getDeclaredMethods())
                    .map(Method::getName).anyMatch(name -> name.contains("dimblend_asyncsave$"));
        }
        return asyncSaveMixinActive;
    }

    private static PerfCheckResult finiteWater() {
        boolean present = ModList.get().isLoaded("finite_water");
        return PerfCheckResult.of(PerfCheck.FINITE_WATER, PerfScope.SERVER,
                present ? PerfStatus.WARN : PerfStatus.OK, present ? "present" : "absent", false);
    }

    private static PerfCheckResult sablePhysics() {
        if (!TuningSettings.available(TuningOption.SABLE_SUBSTEPS)) {
            return PerfCheckResult.of(PerfCheck.SABLE_PHYSICS, PerfScope.SERVER, PerfStatus.UNAVAILABLE,
                    "missing", false, "-", "-", "-", "-", "-");
        }
        boolean defaults = true;
        List<String> args = new ArrayList<>();
        for (TuningOption option : SABLE_PHYSICS) {
            double value = TuningSettings.get(option);
            defaults &= value == option.defaultValue();
            args.add(option.format(value));
        }
        return new PerfCheckResult(PerfCheck.SABLE_PHYSICS, PerfScope.SERVER, PerfStatus.INFO,
                defaults ? "defaults" : "custom", args, false);
    }

    private ServerPerfChecks() {
    }
}
