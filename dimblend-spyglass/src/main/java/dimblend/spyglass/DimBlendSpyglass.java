package dimblend.spyglass;

import com.mojang.logging.LogUtils;
import dimblend.spyglass.client.SpyglassClientSetup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(DimBlendSpyglass.MODID)
public final class DimBlendSpyglass {
    public static final String MODID = "dimblend_spyglass";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DimBlendSpyglass(ModContainer container) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Client config registration and its screen live in the client entry, so common code
            // only names that class behind the dist check (same pattern as the dimblend module).
            SpyglassClientSetup.register(container);
        }
    }
}
