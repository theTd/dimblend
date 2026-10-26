package dimblend.experience.compat.create.observer;

import dimblend.experience.DimBlend;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Registers the observer blaze renderer. The handler body is gated on Create and Sable so the class stays inert
 * when they are absent; no Flywheel visual is registered, which is what keeps the block entity renderer alive on a
 * normal Create client (Create's own burner visual calls {@code skipVanillaRender(true)} for its type).
 */
@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ObserverBlazeClient {
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!ModList.get().isLoaded("create") || !ModList.get().isLoaded("sable")) {
            return;
        }
        event.registerBlockEntityRenderer(ObserverBlazeBlocks.OBSERVER_BLAZE_ENTITY.get(), ObserverBlazeRenderer::new);
    }

    private ObserverBlazeClient() {
    }
}
