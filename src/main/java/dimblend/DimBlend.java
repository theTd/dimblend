package dimblend;

import dimblend.band.BandInfoPayload;
import dimblend.band.BandInfoSync;
import dimblend.band.BandLanePayload;
import dimblend.band.BandLaneSync;
import dimblend.client.DimBlendClient;
import dimblend.command.DimBlendCommands;
import dimblend.diagnostics.HangWatchdog;
import dimblend.compat.CorridorTrackProtector;
import dimblend.compat.CreateTrackGraphCompat;
import dimblend.compat.TerraBlenderRotatingCompat;
import dimblend.compat.VoidscapeBand;
import dimblend.compat.VoidscapeNetherDezombify;
import dimblend.gametest.CopycatObsidianHardnessGameTests;
import dimblend.time.TimeLockPayload;
import dimblend.time.TimeLockSync;
import dimblend.worldgen.ChunkGenMonitor;
import dimblend.worldgen.PregenConfig;
import dimblend.worldgen.PregenController;
import dimblend.worldgen.RegionBoundaryWallProtector;
import dimblend.worldgen.WarpGatePassageGuard;
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
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod("dimblend")
public final class DimBlend {
    private static final PregenController PREGEN = new PregenController();
    private static final ChunkGenMonitor MONITOR = new ChunkGenMonitor();
    private static final HangWatchdog WATCHDOG = new HangWatchdog();
    private static final TimeLockSync TIME_LOCK = new TimeLockSync();
    private static final BandInfoSync BAND_INFO = new BandInfoSync();
    private static final BandLaneSync BAND_LANE = new BandLaneSync();

    public DimBlend(IEventBus modBus, ModContainer container) {
        DimBlendRegistries.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, PregenConfig.SPEC);
        NeoForge.EVENT_BUS.register(PREGEN);
        NeoForge.EVENT_BUS.addListener(DimBlend::onRegisterCommands);
        modBus.addListener(DimBlend::onRegisterPayloads);
        modBus.addListener(DimBlend::onRegisterGameTests);
        NeoForge.EVENT_BUS.register(TIME_LOCK);
        NeoForge.EVENT_BUS.register(BAND_INFO);
        NeoForge.EVENT_BUS.register(BAND_LANE);
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

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        DimBlendCommands.register(event.getDispatcher());
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(CopycatObsidianHardnessGameTests.class);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        TimeLockPayload.register(registrar);
        BandInfoPayload.register(registrar);
        BandLanePayload.register(registrar);
    }
}
