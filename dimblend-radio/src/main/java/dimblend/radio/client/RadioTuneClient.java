package dimblend.radio.client;

import dimblend.radio.net.RadioTunePayloads;

/**
 * The client's way into {@link RadioTuneScreen}.
 *
 * <p>Separate from the screen so {@code RadioNetwork} can register the payload handler without
 * naming a {@code Screen} subclass directly: a dedicated server must never load a client class,
 * and going through this thin holder keeps that boundary obvious.
 */
public final class RadioTuneClient {

    /** S2C entry point: the server answered a request or an edit. */
    public static void receive(RadioTunePayloads.Snapshot snapshot) {
        RadioTuneScreen.receive(snapshot);
    }

    /**
     * Asks the server to open the screen for a jukebox.
     *
     * <p>The client never decides by itself whether that jukebox is a radio: the server owns the
     * radio state, and a client that guessed would be showing a screen for something that is not
     * playing.
     */
    public static void requestOpen(net.minecraft.core.BlockPos pos) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new RadioTunePayloads.Request(pos.immutable()));
    }

    private RadioTuneClient() {
    }
}