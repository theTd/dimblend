package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShapeCodec;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client part of one structure shape.
 *
 * <p>A shape body (see {@link StructureShapeCodec}) is split into fixed-size parts so one structure
 * can never create a single oversized packet. Every part repeats the dimension, the structure key,
 * the region-header revision and its {@code partIndex}/{@code partCount}, so the client can
 * reassemble parts that arrive interleaved with other shapes and can tell a newer revision from a
 * stale one. Only the last part may be shorter than {@link #MAX_PART_BYTES}.
 *
 * <p>The client keeps the parts until it has all {@code partCount} of them, concatenates them in
 * index order and decodes the resulting body.
 *
 * <p>Part size and streaming budget: the protocol allows splitting anywhere above 512 KiB, but a
 * part has to fit one tick's per-player budget or the budget could never be honoured (a 512 KiB part
 * is eight times 64 KiB). Parts are therefore sized to exactly consume one tick: body bytes plus the
 * approximate header equal {@link #STREAM_BUDGET_BYTES_PER_TICK}, so a full part goes out once per
 * tick. Any shape larger than one part is split; every shape above 512 KiB therefore has at least
 * eight parts.
 */
public record SpyglassShapePayload(
        ResourceLocation dimension,
        StructureKey key,
        int revision,
        int partIndex,
        int partCount,
        byte[] data) implements CustomPacketPayload {
    /** Encoded payload bytes a player may receive per tick, header included. */
    public static final int STREAM_BUDGET_BYTES_PER_TICK = 64 * 1024;
    /**
     * Approximate per-part header size (dimension + key + revision + counters), counted against the
     * streaming budget without re-encoding the header. Comfortably above a realistic header so the
     * budget is never undercounted.
     */
    public static final int PART_HEADER_BYTES = 96;
    /** Largest body part; sized so {@code data + PART_HEADER_BYTES} is one tick's budget. */
    public static final int MAX_PART_BYTES = STREAM_BUDGET_BYTES_PER_TICK - PART_HEADER_BYTES;
    /** Parts per shape; together with {@link #MAX_PART_BYTES} this bounds a body to 4 MiB. */
    public static final int MAX_PART_COUNT =
            (StructureShapeCodec.MAX_BODY_BYTES + MAX_PART_BYTES - 1) / MAX_PART_BYTES;

    public static final Type<SpyglassShapePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "structure_shape"));

    public static final StreamCodec<ByteBuf, SpyglassShapePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SpyglassShapePayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            try {
                ResourceLocation dimension = friendly.readResourceLocation();
                ResourceLocation id = friendly.readResourceLocation();
                int startChunkX = friendly.readInt();
                int startChunkZ = friendly.readInt();
                int revision = friendly.readVarInt();
                int partIndex = friendly.readVarInt();
                int partCount = friendly.readVarInt();
                int length = friendly.readableBytes();
                if (length <= 0 || length > MAX_PART_BYTES) {
                    throw new DecoderException("Invalid shape part length: " + length);
                }
                byte[] data = new byte[length];
                friendly.readBytes(data);
                return new SpyglassShapePayload(
                        dimension,
                        new StructureKey(id, startChunkX, startChunkZ),
                        revision,
                        partIndex,
                        partCount,
                        data);
            } catch (DecoderException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new DecoderException("Malformed structure shape part", exception);
            }
        }

        @Override
        public void encode(ByteBuf buf, SpyglassShapePayload value) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeResourceLocation(value.dimension);
            friendly.writeResourceLocation(value.key.id());
            friendly.writeInt(value.key.startChunkX());
            friendly.writeInt(value.key.startChunkZ());
            friendly.writeVarInt(value.revision);
            friendly.writeVarInt(value.partIndex);
            friendly.writeVarInt(value.partCount);
            friendly.writeBytes(value.data);
        }
    };

    public SpyglassShapePayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        if (revision < 0) {
            throw new IllegalArgumentException("Negative shape revision: " + revision);
        }
        if (partCount <= 0 || partCount > MAX_PART_COUNT) {
            throw new IllegalArgumentException("Invalid shape part count: " + partCount);
        }
        if (partIndex < 0 || partIndex >= partCount) {
            throw new IllegalArgumentException("Shape part index " + partIndex + " outside [0, " + partCount + ")");
        }
        if (data.length <= 0 || data.length > MAX_PART_BYTES) {
            throw new IllegalArgumentException("Invalid shape part size: " + data.length);
        }
        if (partIndex < partCount - 1 && data.length != MAX_PART_BYTES) {
            throw new IllegalArgumentException("Non-final shape part is " + data.length + " bytes");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SpyglassShapePayload payload)) {
            return false;
        }
        return this.revision == payload.revision
                && this.partIndex == payload.partIndex
                && this.partCount == payload.partCount
                && this.dimension.equals(payload.dimension)
                && this.key.equals(payload.key)
                && Arrays.equals(this.data, payload.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(this.dimension, this.key, this.revision, this.partIndex, this.partCount);
        return 31 * result + Arrays.hashCode(this.data);
    }

    @Override
    public String toString() {
        return "SpyglassShapePayload["
                + this.dimension
                + ", " + this.key
                + ", revision=" + this.revision
                + ", part=" + this.partIndex + "/" + this.partCount
                + ", bytes=" + this.data.length
                + "]";
    }
}
