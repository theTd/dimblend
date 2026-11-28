package dimblend.mirage;

import java.util.function.Consumer;

/**
 * Registerable client sink for {@link BandSeamInfoPayload}; see {@link MirageClientReceiver} for
 * why the common payload class never references {@code dimblend.client}.
 */
public final class BandSeamInfoClientReceiver {
    private static final Consumer<BandSeamInfoPayload> IGNORE = payload -> {
    };
    private static volatile Consumer<BandSeamInfoPayload> sink = IGNORE;

    private BandSeamInfoClientReceiver() {
    }

    public static void register(Consumer<BandSeamInfoPayload> consumer) {
        sink = consumer == null ? IGNORE : consumer;
    }

    static void accept(BandSeamInfoPayload payload) {
        sink.accept(payload);
    }
}
