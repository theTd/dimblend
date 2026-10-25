package dimblend.spyglass.net;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Common-side indirection for the snapshot payload handler.
 *
 * <p>This class is the only place the network layer talks to, so common code never references a
 * client-only class. The default handler is a no-op (dedicated server); the client installs
 * {@code SpyglassClientState::apply} during client setup.
 */
public final class SpyglassSnapshotHandlers {
    private static volatile Consumer<SpyglassSnapshotPayload> handler = payload -> {
    };

    private SpyglassSnapshotHandlers() {
    }

    public static void install(Consumer<SpyglassSnapshotPayload> snapshotHandler) {
        handler = Objects.requireNonNull(snapshotHandler, "snapshotHandler");
    }

    static void handle(SpyglassSnapshotPayload payload) {
        handler.accept(payload);
    }
}
