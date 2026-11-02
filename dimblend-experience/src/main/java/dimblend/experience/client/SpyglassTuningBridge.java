package dimblend.experience.client;

import dimblend.spyglass.api.SourceCatalog;
import dimblend.spyglass.api.SpyglassTuning;
import java.nio.file.Files;
import java.util.function.Consumer;
import net.neoforged.fml.ModList;

/**
 * The only experience class that links against spyglass's api package. Everything else in the
 * module goes through this bridge, so a jar without spyglass never resolves an api class.
 *
 * <p>{@link #isLoaded()} deliberately checks that the public api class file really exists instead of
 * trusting the mod id: an older spyglass jar can share the same version string (the same reason the
 * core mod's SkyTimeTrace presence check looks at the file).
 */
final class SpyglassTuningBridge {
    static boolean isLoaded() {
        var mod = ModList.get().getModFileById("dimblend_spyglass");
        return mod != null && Files.isRegularFile(
                mod.getFile().findResource("dimblend", "spyglass", "api", "SpyglassTuning.class"));
    }

    static void requestCatalog(Consumer<SourceCatalog> receiver) {
        SpyglassTuning.requestCatalog(receiver);
    }

    static void setSourceEnabled(String namespace, boolean enabled) {
        SpyglassTuning.setSourceEnabled(namespace, enabled);
    }

    static void clearCatalogReceiver() {
        SpyglassTuning.clearCatalogReceiver();
    }

    private SpyglassTuningBridge() {
    }
}
