package dimblend.mirage;

import java.util.function.Consumer;

/**
 * Registerable client sink for {@link MirageChunkPayload}.
 *
 * <p>The payload class lives in common code and must load on a dedicated server; importing
 * {@code dimblend.client.*} from it would drag client-only classes into that classloader. The
 * client module therefore installs its consumer here at startup, and the server-side registration
 * path only ever touches this holder.</p>
 */
public final class MirageClientReceiver {
    private static final Consumer<MirageChunkPayload> IGNORE = payload -> {
    };
    private static volatile Consumer<MirageChunkPayload> sink = IGNORE;

    private MirageClientReceiver() {
    }

    public static void register(Consumer<MirageChunkPayload> consumer) {
        sink = consumer == null ? IGNORE : consumer;
    }

    static void accept(MirageChunkPayload payload) {
        sink.accept(payload);
    }
}
