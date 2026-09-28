package dimblend.compat;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModList;

/**
 * Beautiful Enchanted Books (beb) resolves its platform helper lazily through
 * {@code ServiceLoader.load(IPlatformHelper.class)}, which reads the current
 * thread's context class loader. When that first touch lands on a vanilla
 * worker thread whose context loader is not the game class loader, no provider
 * is found and the resulting ExceptionInInitializerError fails the initial
 * resource reload. Vanilla then retries the reload without selected packs,
 * re-entering NeoForge mod loading and corrupting the mod state. Forcing the
 * initialization here on the mod-loading thread turns the later worker-thread
 * access into a plain static-field read.
 */
public final class BebPlatformWarmup {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SERVICES_CLASS = "com.cerbon.beb.platform.Services";

    public static void warmup() {
        if (!ModList.get().isLoaded("beb")) {
            return;
        }
        try {
            Class.forName(SERVICES_CLASS, true, BebPlatformWarmup.class.getClassLoader());
            LOGGER.info("[dimblend] pre-initialized beb platform services");
        } catch (Throwable t) {
            LOGGER.warn("[dimblend] failed to pre-initialize beb platform services", t);
        }
    }

    private BebPlatformWarmup() {
    }
}
