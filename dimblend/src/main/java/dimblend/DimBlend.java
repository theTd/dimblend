package dimblend;

import dimblend.api.BandTrainMarker;
import dimblend.api.MinimumSpeedState;
import dimblend.api.WarpGateColumns;
import dimblend.band.BandInfoPayload;
import dimblend.band.BandInfoSync;
import dimblend.band.BandLanePayload;
import dimblend.band.BandLaneSync;
import dimblend.band.BandTrainPayload;
import dimblend.client.DimBlendClient;
import dimblend.command.DimBlendCommands;
import dimblend.diagnostics.HangWatchdog;
import dimblend.mirage.BandSeamInfoPayload;
import dimblend.mirage.MirageChunkPayload;
import dimblend.mirage.MirageRequestPayload;
import dimblend.mirage.MirageServerManager;
import dimblend.purge.RegionPurgeConfig;
import dimblend.purge.RegionPurgeController;
import dimblend.compat.CorridorTrackProtector;
import dimblend.compat.CreateTrackGraphCompat;
import dimblend.compat.TerraBlenderRotatingCompat;
import dimblend.compat.VoidscapeBand;
import dimblend.compat.VoidscapeNetherDezombify;
import dimblend.compat.VoidscapeUndeadInfusion;
import dimblend.time.TimeLockPayload;
import dimblend.time.TimeLockSync;
import dimblend.weather.ServerGlobalWeatherLock;
import dimblend.worldgen.ChunkGenMonitor;
import dimblend.worldgen.CorridorLavaOriginals;
import dimblend.worldgen.PregenConfig;
import dimblend.worldgen.PregenController;
import dimblend.worldgen.RegionBoundaryWallProtector;
import dimblend.worldgen.RegionOverrideStore;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.WarpGateColumnsImpl;
import dimblend.worldgen.WarpGatePassageGuard;
import dimblend.worldgen.WarpGateSableCrossing;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod("dimblend")
public final class DimBlend {
    private static final PregenController PREGEN = new PregenController();
    private static final ChunkGenMonitor MONITOR = new ChunkGenMonitor();
    private static final HangWatchdog WATCHDOG = new HangWatchdog();
    private static final RegionPurgeController PURGE = new RegionPurgeController();
    private static final TimeLockSync TIME_LOCK = new TimeLockSync();
    private static final BandInfoSync BAND_INFO = new BandInfoSync();
    private static final BandLaneSync BAND_LANE = new BandLaneSync();

    public DimBlend(IEventBus modBus, ModContainer container) {
        DimBlendRegistries.register(modBus);
        CorridorLavaOriginals.ATTACHMENTS.register(modBus);
        // Public api implementations owned by this module. The override store's data is loaded
        // from the overworld's LevelEvent.Load below, which fires before any chunk generates
        // (see RegionOverrideStore's javadoc for the startup ordering evidence).
        RegionOverrideStore.install();
        WarpGateColumns.Impl.install(new WarpGateColumnsImpl());
        NeoForge.EVENT_BUS.addListener(RegionOverrideStore::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(RegionOverrideStore::onServerStopped);
        NeoForge.EVENT_BUS.addListener(DimBlend::onServerStopped);
        container.registerConfig(ModConfig.Type.SERVER, PregenConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, RegionPurgeConfig.SPEC, RegionPurgeConfig.FILE_NAME);
        container.registerConfig(ModConfig.Type.CLIENT, dimblend.diagnostics.SkyTimeTraceSettings.SPEC);
        NeoForge.EVENT_BUS.register(PREGEN);
        NeoForge.EVENT_BUS.addListener(DimBlend::onRegisterCommands);
        modBus.addListener(DimBlend::onRegisterPayloads);
        modBus.addListener(DimBlend::onRegisterGameTests);
        NeoForge.EVENT_BUS.register(TIME_LOCK);
        NeoForge.EVENT_BUS.register(BAND_INFO);
        NeoForge.EVENT_BUS.register(BAND_LANE);
        NeoForge.EVENT_BUS.addListener(ServerGlobalWeatherLock::onLevelTick);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DimBlendClient.register(modBus);
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TerraBlenderRotatingCompat::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(CreateTrackGraphCompat::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(CreateTrackGraphCompat::onServerTick);
        NeoForge.EVENT_BUS.addListener(CreateTrackGraphCompat::onServerStopped);
        NeoForge.EVENT_BUS.addListener(CorridorTrackProtector::onBreakBlock);
        NeoForge.EVENT_BUS.addListener(CorridorTrackProtector::onExplosionDetonate);
        NeoForge.EVENT_BUS.addListener(RegionBoundaryWallProtector::onBreakBlock);
        NeoForge.EVENT_BUS.addListener(RegionBoundaryWallProtector::onExplosionDetonate);
        NeoForge.EVENT_BUS.addListener(RegionBoundaryWallProtector::onPistonPre);
        NeoForge.EVENT_BUS.addListener(WarpGatePassageGuard::onEntityTickPost);
        NeoForge.EVENT_BUS.addListener(WarpGateSableCrossing::onLevelTick);
        NeoForge.EVENT_BUS.register(MirageServerManager.get());
        NeoForge.EVENT_BUS.addListener(DimBlend::onLevelUnload);
        // dimblend:rotating's delegate placement states may only run their concentric-ring biome
        // searches once OVERWORLD/NETHER/END all exist. Before that, a ring search samples
        // TheEndBiomeSource while Biolith's End noise is still null: the NPE poisons the ring future
        // for the rest of the session and crashes the server on the first End-region chunk.
        // LevelEvent.Load at LOWEST fires inside MinecraftServer#createLevels(), right after each
        // vanilla dimension finished ServerLevel.<init> (so Biolith's per-dimension init ran), and
        // before prepareLevels() forces chunks in dimblend:rotating.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, DimBlend::onLevelLoadInitDelegateStructures);
        // Fallback for custom/minimal presets that omit NETHER or END.
        NeoForge.EVENT_BUS.addListener(DimBlend::onServerStarting);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, VoidscapeNetherDezombify::onSpawnPlacementCheck);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, VoidscapeNetherDezombify::onFinalizeSpawn);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, VoidscapeBand::onPlayerTickEnter);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, VoidscapeBand::onPlayerTickExit);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, VoidscapeBand::onSpawnPlacementEnter);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, VoidscapeBand::onSpawnPlacementExit);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, VoidscapeBand::onSpawnPositionEnter);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, VoidscapeBand::onSpawnPositionExit);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, VoidscapeBand::onFinalizeSpawnEnter);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, VoidscapeBand::onFinalizeSpawnExit);
        NeoForge.EVENT_BUS.addListener(VoidscapeUndeadInfusion::onLivingDrops);
        NeoForge.EVENT_BUS.register(MONITOR);
        NeoForge.EVENT_BUS.register(WATCHDOG);
        NeoForge.EVENT_BUS.addListener(dimblend.diagnostics.SlowTickRecorder::onServerStopping);
        NeoForge.EVENT_BUS.register(PURGE);
    }

    public static HangWatchdog watchdog() {
        return WATCHDOG;
    }

    public static PregenController pregen() {
        return PREGEN;
    }

    public static ChunkGenMonitor monitor() {
        return MONITOR;
    }

    public static RegionPurgeController purge() {
        return PURGE;
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        DimBlendCommands.register(event.getDispatcher());
    }

    /** Server-scoped static state must not survive into the next world of the same client session. */
    private static void onServerStopped(ServerStoppedEvent event) {
        BandTrainMarker.clear();
        MinimumSpeedState.clearAll();
    }

    /** Minimum-speed marks are keyed by dimension and stamped with that level's game time. */
    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            MinimumSpeedState.clear(level.dimension());
        }
    }

    /**
     * Publishes {@code dimblend:rotating}'s delegate structure state once every vanilla dimension
     * exists. Runs at LOWEST inside {@code createLevels()} so it lands after Biolith's
     * {@code MixinServerWorld} and any NORMAL-priority load listener initialized their per-dimension
     * noise, and before {@code prepareLevels()} touches a rotating chunk.
     */
    private static void onLevelLoadInitDelegateStructures(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (RotatingChunkGenerator.readyToInitDelegateStructures(
                server.getLevel(Level.OVERWORLD) != null,
                server.getLevel(Level.NETHER) != null,
                server.getLevel(Level.END) != null)) {
            initRotatingDelegateStructures(server);
        }
    }

    /** Fallback for world presets that omit NETHER or END; idempotent after the LevelEvent.Load hook. */
    private static void onServerStarting(ServerStartingEvent event) {
        initRotatingDelegateStructures(event.getServer());
    }

    private static void initRotatingDelegateStructures(MinecraftServer server) {
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl.getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating) {
                rotating.ensureDelegateStructuresGenerated();
            }
        }
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // "3": M1 adds BandSeamInfoPayload + MirageChunkPayload (S2C) and MirageRequestPayload
        // (C2S). Peers must agree on all three or NeoForge rejects the connection. "2" added the
        // BandTrainPayload alert bit; "1" was the first band sync.
        PayloadRegistrar registrar = event.registrar("3");
        TimeLockPayload.register(registrar);
        BandInfoPayload.register(registrar);
        BandLanePayload.register(registrar);
        BandTrainPayload.register(registrar);
        MirageRequestPayload.register(registrar);
        MirageChunkPayload.register(registrar);
        BandSeamInfoPayload.register(registrar);
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(dimblend.gametest.CorridorLavaSourceGameTests.class);
    }
}
