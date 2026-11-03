package dimblend.spyglass;

import com.mojang.logging.LogUtils;
import dimblend.spyglass.server.SpyglassServerSettings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(DimBlendSpyglass.MODID)
public final class DimBlendSpyglass {
    public static final String MODID = "dimblend_spyglass";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DimBlendSpyglass(IEventBus modEventBus, ModContainer container) {
        // Common on both sides: the source filter is server-side data, and NeoForge syncs the file
        // to clients on login. The listeners are common too; the reloading one invalidates scans.
        container.registerConfig(ModConfig.Type.SERVER, SpyglassServerSettings.SPEC);
        modEventBus.addListener(SpyglassServerSettings::onLoading);
        modEventBus.addListener(SpyglassServerSettings::onReloading);
        modEventBus.addListener(SpyglassServerSettings::onUnloading);
        // The client-side snapshot handler install and the client GUI layers register themselves
        // through @EventBusSubscriber; there is no client config and no client setup call any more.
    }
}
