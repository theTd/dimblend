package dimblend.spyglass.net;

import dimblend.spyglass.api.SourceCatalog;
import dimblend.spyglass.api.SpyglassTuning;

/**
 * Common-side indirection for the source catalog: the network layer never references a client
 * class. The catalog is pure data in the api package, and {@link SpyglassTuning} drops it when no
 * receiver is installed (dedicated server), so this handler is safe on both sides.
 */
public final class SpyglassSourceHandlers {
    public static void handle(SpyglassSourcesPayload payload) {
        SpyglassTuning.acceptCatalog(
                new SourceCatalog(payload.sources(), payload.editable(), payload.message()));
    }

    private SpyglassSourceHandlers() {
    }
}
