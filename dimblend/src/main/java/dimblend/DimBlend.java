package dimblend;

import dimblend.api.BandTrainMarker;
import dimblend.api.WarpGateColumns;
import dimblend.band.BandInfoPayload;
import dimblend.band.BandInfoSync;
import dimblend.band.BandLanePayload;
import dimblend.band.BandLaneSync;
import dimblend.band.BandTrainPayload;
import dimblend.client.DimBlendClient;
import dimblend.command.DimBlendCommands;
import dimblend.diagnostics.HangWatchdog;
import dimblend.purge.RegionPurgeConfig;
import dimblend.purge.RegionPurgeController;
import dimblend.compat.CorridorTrackProtector;
import dimblend.compat.CreateTrackGraphCompat;
import dimblend.compat.TerraBlenderRotatingCompat;
import dimblend.compat.VoidscapeBand;
import dimblend.compat.VoidscapeNetherDezombify;
import dimblend.time.TimeLockPayload;
import dimblend.time.TimeLockSync;
import dimblend.weather.ServerGlobalWeatherLock;
import dimblend.worldgen.ChunkGenMonitor;
import dimblend.worldgen.CorridorLavaOriginals;
import dimblend.worldgen.PregenConfig;
import dimblend.worldgen.PregenController;
import dimblend.worldgen.RegionBoundaryWallProtector;
import dimblend.worldgen.RegionOverrideStore;
import dimblend.worldgen.WarpGateColumnsImpl;
import dimblend.worldgen.WarpGatePassageGuard;
import dimblend.worldgen.WarpGateSableCrossing;
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
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // "2": BandTrainPayload gained the alert bit (dragon-chase marker). A peer still speaking
        // "1" would mis-decode the extra boolean, so let NeoForge reject the connection instead.
        PayloadRegistrar registrar = event.registrar("2");
        TimeLockPayload.register(registrar);
        BandInfoPayload.register(registrar);
        BandLanePayload.register(registrar);
        BandTrainPayload.register(registrar);
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(dimblend.gametest.CorridorLavaSourceGameTests.class);
    }
}
