package dimblend.radio.net;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.RadioVolumeRule;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The jukebox tuning screen's three messages, in one file because they are one conversation.
 *
 * <p>The user's requirement moved the radio knobs out of {@code dbx tune} and into "右键唱片机":
 * tuning a radio has to be something done at that radio, not from a world-wide panel. The three
 * messages below are what that takes.
 *
 * <ul>
 *   <li>{@link Request} (C2S): "open the screen for the jukebox at this position". The server
 *       checks that a jukebox with a running radio is really there before it answers — a client
 *       cannot open the screen for a block that does not exist.</li>
 *   <li>{@link Snapshot} (S2C): the current values, so the screen renders server truth and two
 *       players looking at one jukebox see the same numbers.</li>
 *   <li>{@link Edit} (C2S): one changed knob. The server validates the value and the permission
 *       again; the screen's own checks are a convenience, never the authority.</li>
 * </ul>
 *
 * <p>The four global radio knobs travel as {@code double} in the snapshot so the screen does not
 * need to know their order — see {@link #GLOBAL_OPTIONS}. Keeping them in one array means adding a
 * fifth knob later is one array slot, not four more wire fields.
 */
public final class RadioTunePayloads {

    /** The per-jukebox volume; not one of the global options. */
    public static final String VOLUME_OPTION = "jukebox_volume";

    /** Order of the four global values in {@link Snapshot#globals()}. */
    public static final String[] GLOBAL_OPTIONS = {
            "radio_static", "radio_end_voice_frequency", "radio_end_voice_volume", "radio_acoustic_intensity"
    };

    /**
     * C2S: open the jukebox screen.
     *
     * <p>Sent when the player right-clicks an empty radio jukebox. The server answers with a
     * {@link Snapshot}, which is what actually opens the screen — so a client that lies about the
     * position opens nothing.
     */
    public record Request(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "radio_tune_request"));

        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Request decode(ByteBuf buf) {
                return new Request(new FriendlyByteBuf(buf).readBlockPos());
            }

            @Override
            public void encode(ByteBuf buf, Request value) {
                new FriendlyByteBuf(buf).writeBlockPos(value.pos());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: the screen's whole state.
     *
     * @param pos          the jukebox this screen is for
     * @param volumePercent that jukebox's own setting, {@link RadioVolumeRule#DYNAMIC} or 10..150
     * @param globals      the four global values, in {@link #GLOBAL_OPTIONS} order
     * @param editable     whether this player may change anything
     * @param message      a lang suffix the screen shows, or "" for none
     */
    public record Snapshot(BlockPos pos, int volumePercent, double[] globals, boolean editable, String message)
            implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "radio_tune_snapshot"));

        public static final StreamCodec<ByteBuf, Snapshot> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Snapshot decode(ByteBuf buf) {
                FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                BlockPos pos = friendly.readBlockPos();
                int volume = friendly.readVarInt();
                double[] globals = new double[GLOBAL_OPTIONS.length];
                for (int i = 0; i < globals.length; i++) {
                    globals[i] = friendly.readDouble();
                }
                return new Snapshot(pos, volume, globals, friendly.readBoolean(), friendly.readUtf());
            }

            @Override
            public void encode(ByteBuf buf, Snapshot value) {
                FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                friendly.writeBlockPos(value.pos());
                friendly.writeVarInt(value.volumePercent());
                for (double global : value.globals()) {
                    friendly.writeDouble(global);
                }
                friendly.writeBoolean(value.editable());
                friendly.writeUtf(value.message());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: one knob changed.
     *
     * @param pos    the jukebox
     * @param option one of {@link #GLOBAL_OPTIONS}, or {@link #VOLUME_OPTION} for the per-block value
     * @param value  the requested value
     */
    public record Edit(BlockPos pos, String option, double value) implements CustomPacketPayload {
        public static final Type<Edit> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "radio_tune_edit"));

        public static final StreamCodec<ByteBuf, Edit> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Edit decode(ByteBuf buf) {
                FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                return new Edit(friendly.readBlockPos(), friendly.readUtf(), friendly.readDouble());
            }

            @Override
            public void encode(ByteBuf buf, Edit value) {
                FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                friendly.writeBlockPos(value.pos());
                friendly.writeUtf(value.option());
                friendly.writeDouble(value.value());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RadioTunePayloads() {
    }
}