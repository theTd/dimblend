package dimblend.client;

import dimblend.client.bandrift.BandFogTransition;
import dimblend.client.bandrift.BandRiftCommands;
import dimblend.client.bandrift.BandRiftRenderer;
import dimblend.client.bandrift.BandRiftShaders;
import dimblend.client.mirage.ClientMirageManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry points. Only referenced behind a
 * {@code FMLEnvironment.dist == Dist.CLIENT} check in {@link dimblend.DimBlend}
 * so a dedicated server never loads this class or the client-only HUD classes
 * it touches.
 */
public final class DimBlendClient {
    private DimBlendClient() {
    }

    /** Wires every client-only listener; called behind the Dist.CLIENT check. */
    public static void register(IEventBus modBus) {
        dimblend.compat.BebPlatformWarmup.warmup();
        modBus.addListener(DimBlendClient::onRegisterGuiLayers);
        modBus.addListener(DimBlendClient::onRegisterDimensionSpecialEffects);
        modBus.addListener(BandRiftShaders::onRegisterShaders);
        // Mirage payload sinks + seam discovery (M1 client half).
        ClientMirageManager.register();
        NeoForge.EVENT_BUS.addListener(TwilightBandFog::onRenderFog);
        NeoForge.EVENT_BUS.addListener(TwilightBandFog::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(RotatingDimensionEffects::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ClientSkyTimeTrace::onFrame);
        NeoForge.EVENT_BUS.addListener(VoidscapeMachineTooltips::onItemTooltip);
        // Band-boundary frameless membrane + crossing burst (Phase 1) and fog transition (Phase 2).
        // The fog handlers run at LOW so TwilightBandFog's default-priority curve is the value
        // being blended towards instead of being overwritten by it.
        NeoForge.EVENT_BUS.addListener(BandRiftRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(BandRiftRenderer::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientMirageManager::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientMirageManager::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ClientMirageManager::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(BandRiftRenderer::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(BandRiftRenderer::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(BandFogTransition::onClientTick);
        NeoForge.EVENT_BUS.addListener(BandFogTransition::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, BandFogTransition::onComputeFogColor);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, BandFogTransition::onRenderFog);
        NeoForge.EVENT_BUS.addListener(BandRiftCommands::onRegisterClientCommands);
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // Wrap the vanilla hotbar so the band progress bar is drawn in the
        // bottom-anchored hotbar strip, immediately after the hotbar itself.
        event.wrapLayer(VanillaGuiLayers.HOTBAR, layer -> (graphics, partialTick) -> {
            layer.render(graphics, partialTick);
            BandProgressHud.render(graphics);
        });
    }

    private static void onRegisterDimensionSpecialEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(
                ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"),
                new RotatingDimensionEffects());
    }
}
