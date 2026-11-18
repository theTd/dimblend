package dimblend.api;

/**
 * Endpoint-agnostic client-visible chase activity flag shared between {@code dimblend-dragonchase}
 * (writer on S2C chase state sync and client logout) and {@code dimblend-experience} (reader in
 * optional client compat mixins such as revealing End corridor phantom tracks while a chase is
 * active).
 *
 * <p>Zero third-party or client-only imports so safe to load in any environment; when
 * {@code dimblend-dragonchase} is absent or idle, {@link #isChaseActive()} remains {@code false}.
 */
public final class DragonChaseClientState {

    private static volatile boolean chaseActive;

    public static boolean isChaseActive() {
        return chaseActive;
    }

    public static void setChaseActive(boolean active) {
        chaseActive = active;
    }

    private DragonChaseClientState() {
    }
}
