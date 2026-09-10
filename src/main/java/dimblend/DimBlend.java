package dimblend;

import dimblend.command.DimBlendCommands;
import dimblend.diagnostics.HangWatchdog;
import dimblend.compat.CreateTrackGraphCompat;
import dimblend.compat.TerraBlenderRotatingCompat;
import dimblend.worldgen.ChunkGenMonitor;
import dimblend.worldgen.PregenConfig;
import dimblend.worldgen.PregenController;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod("dimblend")
public final class DimBlend {
    private static final PregenController PREGEN = new PregenController();
    private static final ChunkGenMonitor MONITOR = new ChunkGenMonitor();
    private static final HangWatchdog WATCHDOG = new HangWatchdog();

    public DimBlend(IEventBus modBus, ModContainer container) {
        DimBlendRegistries.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, PregenConfig.SPEC);
        NeoForge.EVENT_BUS.register(PREGEN);
        NeoForge.EVENT_BUS.addListener(DimBlend::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TerraBlenderRotatingCompat::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(CreateTrackGraphCompat::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(CreateTrackGraphCompat::onServerTick);
        NeoForge.EVENT_BUS.addListener(CreateTrackGraphCompat::onServerStopped);
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
}
