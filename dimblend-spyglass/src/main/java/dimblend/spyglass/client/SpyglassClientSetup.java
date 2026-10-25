package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassShapeHandlers;
import dimblend.spyglass.net.SpyglassSnapshotHandlers;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only wiring: registers the fog-strength config and its in-game screen (called from the mod
 * constructor on the client), installs the snapshot and shape handlers in the common-side hooks so
 * common/net code never references a client class, and drops the structure meshes on every resource
 * reload (block models and atlas UVs change, so the baked vertex data is stale).
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class SpyglassClientSetup {
    /**
     * Registers the live client config and NeoForge's built-in config screen. Called from the mod
     * constructor only on the client, so common code never loads a client-only class.
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SpyglassClientConfig.SPEC);
        // ConfigurationScreen reads and writes ModConfigSpec values; method reference picks its
        // (ModContainer, Screen) constructor, the shape IConfigScreenFactory requires.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        SpyglassSnapshotHandlers.install(SpyglassClientState::apply);
        SpyglassShapeHandlers.install(SpyglassShapeCache::receive);
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // onResourceManagerReload runs in the apply phase on the client thread, where GL buffers may
        // be deleted; ShapeMeshStore.clear also bumps its epoch so in-flight builds are discarded.
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> ShapeMeshStore.clear());
    }

    private SpyglassClientSetup() {
    }
}
