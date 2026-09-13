package dimblend.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry points. Only referenced behind a
 * {@code FMLEnvironment.dist == Dist.CLIENT} check in {@link dimblend.DimBlend}
 * so a dedicated server never loads this class or the client-only classes
 * it touches.
 */
public final class DimBlendClient {
    private DimBlendClient() {
    }

    /** Wires every client-only listener; called behind the Dist.CLIENT check. */
    public static void register(IEventBus modBus) {
        modBus.addListener(DimBlendClient::onRegisterDimensionSpecialEffects);
        NeoForge.EVENT_BUS.addListener(TwilightBandFog::onRenderFog);
        NeoForge.EVENT_BUS.addListener(TwilightBandFog::onLevelUnload);
    }

    private static void onRegisterDimensionSpecialEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(
                ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"),
                new RotatingDimensionEffects());
    }
}
