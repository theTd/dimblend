package dimblend.client;

import net.minecraft.resources.ResourceLocation;
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
        modBus.addListener(DimBlendClient::onRegisterGuiLayers);
        modBus.addListener(DimBlendClient::onRegisterDimensionSpecialEffects);
        NeoForge.EVENT_BUS.addListener(TwilightBandFog::onRenderFog);
        NeoForge.EVENT_BUS.addListener(TwilightBandFog::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(RotatingDimensionEffects::onLevelUnload);
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
