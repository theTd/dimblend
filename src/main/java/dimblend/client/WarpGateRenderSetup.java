package dimblend.client;

import dimblend.DimBlendRegistries;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Binds the vanilla end-gateway starfield renderer to the warp gate block entity type.
 * Kept out of the main mod class so the server never classloads client renderer code.
 */
@EventBusSubscriber(modid = "dimblend", value = Dist.CLIENT)
public final class WarpGateRenderSetup {
    private WarpGateRenderSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(DimBlendRegistries.WARP_GATE_BE.get(), TheEndGatewayRenderer::new);
    }
}
