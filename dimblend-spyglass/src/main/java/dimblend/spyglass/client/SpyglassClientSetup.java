package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassSnapshotHandlers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only wiring for the payload handlers.
 *
 * <p>The spyglass streams no structure shapes any more, so the only handler left to install is the
 * snapshot one; the client fog config, its screen and the mesh-cache reload listener were removed
 * together with the block rendering. Common network code never references a client class (see
 * {@link SpyglassSnapshotHandlers}).
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class SpyglassClientSetup {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        SpyglassSnapshotHandlers.install(SpyglassClientState::apply);
    }

    private SpyglassClientSetup() {
    }
}
