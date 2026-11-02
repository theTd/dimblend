package dimblend.spyglass.api;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassSourceEditPayload;
import dimblend.spyglass.server.SpyglassServerService;
import dimblend.spyglass.server.SpyglassServerSettings;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The only entry point another mod may call: source toggle catalog and edits.
 *
 * <p>Contract: {@code docs/design/api/experience-spyglass.md} §3.2. Every method is static, never
 * throws, and is safe to link on both sides. The catalog is computed on the server; the client can
 * only ask for it asynchronously, so a receiver is installed once and fed on every server reply.
 */
public final class SpyglassTuning {
    /** Installed by the panel; fed on every server catalog reply. Client thread. */
    private static volatile Consumer<SourceCatalog> catalogReceiver;

    // ---------- presence check (any thread, no side effects) ----------

    /**
     * Whether spyglass's SERVER config is initialised. The experience panel does <b>not</b> use this
     * to decide if the button appears; that is its own API-file check (see the boundary contract
     * §7.2). This method is for spyglass's own code.
     */
    public static boolean isLoaded() {
        return SpyglassServerSettings.SPEC.isLoaded();
    }

    // ---------- client ----------

    /**
     * Installs the receiver and immediately asks the server for the catalog. Client thread; sends one
     * C2S packet. Calling it again replaces the receiver.
     */
    public static void requestCatalog(Consumer<SourceCatalog> receiver) {
        catalogReceiver = receiver;
        sendEdit(new SpyglassSourceEditPayload("", false, true));
    }

    /**
     * Turns one source off or on. Client thread; sends one C2S packet. Permission is checked on the
     * server only, and no state is cached here. The result arrives through the receiver installed by
     * {@link #requestCatalog}; without one the reply is dropped.
     */
    public static void setSourceEnabled(String namespace, boolean enabled) {
        sendEdit(new SpyglassSourceEditPayload(namespace == null ? "" : namespace, enabled, false));
    }

    /** Drops the receiver (disconnect / screen closed) so it cannot hold a dead {@code Screen}. */
    public static void clearCatalogReceiver() {
        catalogReceiver = null;
    }

    // ---------- shared ----------

    /**
     * Delivers a server catalog reply to the installed receiver. Called by
     * {@code net.SpyglassSourceHandlers} on the client main thread; not part of the experience
     * contract, but public to that handler.
     */
    public static void acceptCatalog(SourceCatalog catalog) {
        Consumer<SourceCatalog> receiver = catalogReceiver;
        if (receiver != null) {
            receiver.accept(catalog);
        }
    }

    // ---------- server ----------

    /** The namespaces currently disabled: immutable, empty while the config is unloaded, never {@code minecraft}. */
    public static Set<String> disabledSources() {
        Set<String> normalized = new LinkedHashSet<>();
        for (String namespace : SpyglassServerSettings.disabledSources()) {
            if ("minecraft".equals(namespace)) {
                continue;
            }
            normalized.add(namespace);
        }
        return Set.copyOf(normalized);
    }

    /**
     * Forces every currently scoping player's next poll to rebuild its snapshot. Server thread.
     * <b>This class checks no permission</b>: the only caller is the server payload handler, which
     * validated level 2 before calling it (boundary contract §4).
     */
    public static void invalidateSnapshots() {
        SpyglassServerService.invalidateAll();
    }

    private static void sendEdit(SpyglassSourceEditPayload payload) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            // Dedicated server: nobody to send to, and sending from the server thread would throw.
            return;
        }
        try {
            PacketDistributor.sendToServer(payload);
        } catch (RuntimeException exception) {
            // No connection yet (e.g. the panel was opened while logging out); never fail the UI.
            DimBlendSpyglass.LOGGER.debug("Could not send spyglass source packet: {}", exception.toString());
        }
    }

    @Nullable
    static Consumer<SourceCatalog> receiver() {
        return catalogReceiver;
    }

    private SpyglassTuning() {
    }
}