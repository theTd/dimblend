package dimblend.client;

import dimblend.DimBlendRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Binds the vanilla end-gateway starfield renderer to the warp gate block entity type.
 * Kept out of the main mod class so the server never classloads client renderer code.
 *
 * Registered twice on purpose. The official path is {@code EntityRenderersEvent.RegisterRenderers},
 * but FML refuses every mod-bus event once ANY mod's common setup has failed (a broken
 * mod state, common in big packs) — without a renderer the gate block, which renders no
 * static model, would be fully invisible. The game bus is unaffected by that state, so
 * {@link #onClientTick} lazily performs the same idempotent registration on the first
 * client tick.
 */
@EventBusSubscriber(modid = "dimblend", value = Dist.CLIENT)
public final class WarpGateRenderSetup {
    private static boolean registered;

    private WarpGateRenderSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        register(false);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        register(true);
    }

    private static void register(boolean latePoke) {
        if (registered) {
            return;
        }
        registered = true;
        BlockEntityRenderers.register(DimBlendRegistries.WARP_GATE_BE.get(), TheEndGatewayRenderer::new);
        if (!latePoke) {
            // Official path: the initial resource reload runs after this event and rebuilds
            // the dispatcher snapshot, so no manual poke is needed here. Poking now would
            // eagerly instantiate every mod's block entity renderers before their resources
            // (e.g. EnderStorage's OBJ model) are loadable and crash the client.
            return;
        }
        // The dispatcher renders from a snapshot rebuilt only on resource reload; poke one
        // rebuild so the late registration takes effect immediately instead of relying on
        // the initial reload losing the race against the first client tick.
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getBlockEntityRenderDispatcher().onResourceManagerReload(minecraft.getResourceManager());
    }
}
