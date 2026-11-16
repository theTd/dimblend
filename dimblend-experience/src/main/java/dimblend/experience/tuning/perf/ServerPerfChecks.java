package dimblend.experience.tuning.perf;

import dimblend.experience.DimBlend;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSettings;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
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
    /** {@code Level.blockEntityTickers}, the live list of ticking block entities, read once. */
    private static volatile Field blockEntityTickers;
    private static volatile boolean blockEntityTickersFailed;

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
        results.add(mobSpawnCaps());
        results.add(randomTickSpeed(server));
        results.add(chuteContraptionScan(server));
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
            case MOB_SPAWN_CAPS -> {
                // Easy Mob Spawn Control's spawnCaps lowers MobCategory.max in memory, which makes
                // SpawnState.canSpawnForCategory stop the spawner in O(1) instead of per chunk.
                return EasyMobSpawnControlBridge.applyLimits();
            }
            case RANDOM_TICK_SPEED -> {
                server.getGameRules().getRule(GameRules.RULE_RANDOMTICKING)
                        .set(PerfRules.RANDOM_TICK_SPEED_MAX, server);
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

    /**
     * The per-chunk caps every category's {@code SpawnState.canSpawnForCategory} compares against.
     * Vanilla's 70 / 15 / 20 rarely fill in a world stitched from several biome mods, so the spawner
     * keeps scanning every chunk (26.2% of the 2026-11 travel tick). Easy Mob Spawn Control's
     * spawnCaps lowers the caps in place; its spawnRates would cancel FinalizeSpawnEvent and keep
     * them from ever filling.
     */
    private static PerfCheckResult mobSpawnCaps() {
        int monster = MobCategory.MONSTER.getMaxInstancesPerChunk();
        int ambient = MobCategory.AMBIENT.getMaxInstancesPerChunk();
        int waterAmbient = MobCategory.WATER_AMBIENT.getMaxInstancesPerChunk();
        String[] args = {Integer.toString(monster), Integer.toString(ambient), Integer.toString(waterAmbient),
                Integer.toString(PerfRules.MONSTER_SPAWN_CAP_MAX),
                Integer.toString(PerfRules.AMBIENT_SPAWN_CAP_MAX),
                Integer.toString(PerfRules.WATER_AMBIENT_SPAWN_CAP_MAX)};
        if (PerfRules.mobSpawnCapsStatus(monster, ambient, waterAmbient) == PerfStatus.OK) {
            return PerfCheckResult.of(PerfCheck.MOB_SPAWN_CAPS, PerfScope.SERVER, PerfStatus.OK, "ok", false, args);
        }
        if (!ModList.get().isLoaded("easy_mob_spawn_control")) {
            // Installed advice, not a fixable warning: the caps can only be lowered through that mod.
            return PerfCheckResult.of(PerfCheck.MOB_SPAWN_CAPS, PerfScope.SERVER, PerfStatus.INFO,
                    "missing", false, args);
        }
        return PerfCheckResult.of(PerfCheck.MOB_SPAWN_CAPS, PerfScope.SERVER, PerfStatus.WARN, "high", true, args);
    }

    private static PerfCheckResult randomTickSpeed(MinecraftServer server) {
        int speed = server.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        boolean high = speed > PerfRules.RANDOM_TICK_SPEED_MAX;
        return PerfCheckResult.of(PerfCheck.RANDOM_TICK_SPEED, PerfScope.SERVER,
                PerfRules.randomTickSpeedStatus(speed), high ? "high" : "ok", high,
                Integer.toString(speed), Integer.toString(PerfRules.RANDOM_TICK_SPEED_MAX));
    }

    /**
     * Counts ticking Create chutes, each of which asks create_connected's contraption inventory
     * access handler for its inventories once per tick (15.56 s of the 2026-11 travel capture).
     * The count reads vanilla's per-level ticker list, so no chunk is loaded and no Create class is
     * referenced.
     */
    private static PerfCheckResult chuteContraptionScan(MinecraftServer server) {
        String max = Integer.toString(PerfRules.TICKING_CHUTES_MAX);
        if (!ModList.get().isLoaded("create") || !ModList.get().isLoaded("create_connected")) {
            return PerfCheckResult.of(PerfCheck.CHUTE_CONTRAPTION_SCAN, PerfScope.SERVER,
                    PerfStatus.UNAVAILABLE, "absent", false, "-", max);
        }
        Field field = blockEntityTickers();
        if (field == null) {
            return PerfCheckResult.of(PerfCheck.CHUTE_CONTRAPTION_SCAN, PerfScope.SERVER,
                    PerfStatus.UNAVAILABLE, "absent", false, "-", max);
        }
        int count;
        try {
            count = countTickingChutes(server, field);
        } catch (IllegalAccessException | RuntimeException failure) {
            blockEntityTickersFailed = true;
            blockEntityTickers = null;
            DimBlend.LOGGER.warn("Could not scan ticking block entities for Create chutes", failure);
            return PerfCheckResult.of(PerfCheck.CHUTE_CONTRAPTION_SCAN, PerfScope.SERVER,
                    PerfStatus.UNAVAILABLE, "absent", false, "-", max);
        }
        boolean high = count > PerfRules.TICKING_CHUTES_MAX;
        return PerfCheckResult.of(PerfCheck.CHUTE_CONTRAPTION_SCAN, PerfScope.SERVER,
                PerfRules.tickingChutesStatus(count), high ? "high" : "ok", false,
                Integer.toString(count), max);
    }

    /**
     * {@code Level.blockEntityTickers} holds the live ticking block entities in Mojmap runtime
     * names. A missing or unreadable field disables the scan for the whole session (one warning).
     */
    private static Field blockEntityTickers() {
        if (blockEntityTickersFailed) {
            return null;
        }
        Field field = blockEntityTickers;
        if (field != null) {
            return field;
        }
        try {
            field = Level.class.getDeclaredField("blockEntityTickers");
            field.setAccessible(true);
        } catch (NoSuchFieldException | RuntimeException failure) {
            blockEntityTickersFailed = true;
            DimBlend.LOGGER.warn("Could not read Level.blockEntityTickers; the Create chute scan is unavailable", failure);
            return null;
        }
        blockEntityTickers = field;
        return field;
    }

    private static int countTickingChutes(MinecraftServer server, Field field) throws IllegalAccessException {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (!(field.get(level) instanceof List<?> tickers)) {
                continue;
            }
            for (Object entry : tickers) {
                if (entry instanceof TickingBlockEntity ticker && !ticker.isRemoved()
                        && PerfRules.isCreateChuteTickerType(ticker.getType())) {
                    count++;
                }
            }
        }
        return count;
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
