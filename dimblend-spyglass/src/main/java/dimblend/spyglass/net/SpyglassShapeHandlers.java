package dimblend.spyglass.net;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Common-side indirection for the shape payload handler, mirroring
 * {@link SpyglassSnapshotHandlers}.
 *
 * <p>The default handler is a no-op (dedicated server); the client installs
 * {@code SpyglassShapeCache::receive} during client setup, so common code never references a
 * client-only class.
 */
public final class SpyglassShapeHandlers {
    private static volatile Consumer<SpyglassShapePayload> handler = payload -> {
    };

    private SpyglassShapeHandlers() {
    }

    public static void install(Consumer<SpyglassShapePayload> shapeHandler) {
        handler = Objects.requireNonNull(shapeHandler, "shapeHandler");
    }

    static void handle(SpyglassShapePayload payload) {
        handler.accept(payload);
    }
}
