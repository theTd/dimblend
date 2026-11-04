package dimblend.spyglass.net;

/**
 * Common-side holder for the client-visible "a scan is in flight" flag.
 *
 * <p>The flag is only read by the client HUD, but it lives in the common network package so the
 * payload registration never references a client-only class (same indirection as the snapshot
 * handler). The value is reset whenever the client drops its level state.
 */
public final class SpyglassBusyHandlers {
    private static volatile boolean busy;

    /** Payload handler; runs on the client main thread (the registrar wraps handlers by default). */
    static void handle(SpyglassBusyPayload payload) {
        busy = payload.busy();
    }

    /** True while the server is still computing the snapshot for this scoping player. */
    public static boolean isBusy() {
        return busy;
    }

    /** Forgets the flag; called when the client drops its level, dimension or connection. */
    public static void reset() {
        busy = false;
    }

    private SpyglassBusyHandlers() {
    }
}
