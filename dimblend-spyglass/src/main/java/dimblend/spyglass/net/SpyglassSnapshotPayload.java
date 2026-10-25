package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server → client structure snapshot. Wire format (protocol version 4): dimension, sequence,
 * structure count, then per structure: the structure key (id, start chunk coordinates), union bounds
 * (6 ints), piece count, and the pieces (6 ints each).
 *
 * <p>The key was added in protocol 3 so a streamed {@link SpyglassShapePayload} can be linked to the
 * outline it belongs to; the client cache is keyed by it. Protocol 4 added the client's reveal
 * distance ({@link SpyglassRevealPayload}), which only changes which structures the server keeps in
 * this snapshot, not the wire format itself.
 */
public record SpyglassSnapshotPayload(
        ResourceLocation dimension,
        long sequence,
        List<StructureOutline> structures) implements CustomPacketPayload {
    /** Hard caps, see dimblend-spyglass/README.md. */
    public static final int MAX_STRUCTURES = 256;
    public static final int MAX_PIECES_PER_STRUCTURE = 4096;
    public static final int MAX_TOTAL_PIECES = 16384;

    public static final Type<SpyglassSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "structure_snapshot"));

    public static final StreamCodec<ByteBuf, SpyglassSnapshotPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SpyglassSnapshotPayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            ResourceLocation dimension = friendly.readResourceLocation();
            long sequence = friendly.readLong();
            int structureCount = readCount(friendly, MAX_STRUCTURES, "structures");
            List<StructureOutline> structures = new ArrayList<>(structureCount);
            int totalPieces = 0;
            for (int i = 0; i < structureCount; i++) {
                ResourceLocation id = friendly.readResourceLocation();
                int startChunkX = friendly.readInt();
                int startChunkZ = friendly.readInt();
                StructureKey key = readKey(id, startChunkX, startChunkZ);
                OutlineBox bounds = readBox(friendly);
                int pieceCount = readCount(friendly, MAX_PIECES_PER_STRUCTURE, "pieces");
                if (pieceCount == 0) {
                    throw new DecoderException("Empty structure outline");
                }
                totalPieces = addPieces(totalPieces, pieceCount);
                if (totalPieces > MAX_TOTAL_PIECES) {
                    throw new DecoderException("Too many outline pieces: " + totalPieces);
                }
                List<OutlineBox> pieces = new ArrayList<>(pieceCount);
                for (int j = 0; j < pieceCount; j++) {
                    pieces.add(readBox(friendly));
                }
                structures.add(createOutline(key, bounds, pieces));
            }
            return new SpyglassSnapshotPayload(dimension, sequence, structures);
        }

        @Override
        public void encode(ByteBuf buf, SpyglassSnapshotPayload value) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeResourceLocation(value.dimension);
            friendly.writeLong(value.sequence);
            friendly.writeVarInt(value.structures.size());
            for (StructureOutline structure : value.structures) {
                friendly.writeResourceLocation(structure.key().id());
                friendly.writeInt(structure.key().startChunkX());
                friendly.writeInt(structure.key().startChunkZ());
                writeBox(friendly, structure.bounds());
                friendly.writeVarInt(structure.pieces().size());
                for (OutlineBox piece : structure.pieces()) {
                    writeBox(friendly, piece);
                }
            }
        }
    };

    public SpyglassSnapshotPayload {
        Objects.requireNonNull(dimension, "dimension");
        structures = List.copyOf(structures);
        if (structures.size() > MAX_STRUCTURES) {
            throw new IllegalArgumentException("Too many structures: " + structures.size());
        }
        int totalPieces = 0;
        for (StructureOutline structure : structures) {
            if (structure.pieces().size() > MAX_PIECES_PER_STRUCTURE) {
                throw new IllegalArgumentException("Too many pieces in " + structure.id());
            }
            totalPieces = Math.addExact(totalPieces, structure.pieces().size());
        }
        if (totalPieces > MAX_TOTAL_PIECES) {
            throw new IllegalArgumentException("Too many outline pieces: " + totalPieces);
        }
    }

    private static void writeBox(FriendlyByteBuf buf, OutlineBox box) {
        buf.writeInt(box.minX());
        buf.writeInt(box.minY());
        buf.writeInt(box.minZ());
        buf.writeInt(box.maxX());
        buf.writeInt(box.maxY());
        buf.writeInt(box.maxZ());
    }

    private static OutlineBox readBox(FriendlyByteBuf buf) {
        int minX = buf.readInt();
        int minY = buf.readInt();
        int minZ = buf.readInt();
        int maxX = buf.readInt();
        int maxY = buf.readInt();
        int maxZ = buf.readInt();
        try {
            return new OutlineBox(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid outline box: " + exception.getMessage(), exception);
        }
    }

    private static StructureKey readKey(ResourceLocation id, int startChunkX, int startChunkZ) {
        try {
            return new StructureKey(id, startChunkX, startChunkZ);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid structure key: " + exception.getMessage(), exception);
        }
    }

    private static StructureOutline createOutline(
            StructureKey key, OutlineBox bounds, List<OutlineBox> pieces) {
        try {
            return new StructureOutline(key, bounds, pieces);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid structure outline: " + exception.getMessage(), exception);
        }
    }

    private static int addPieces(int totalPieces, int pieceCount) {
        try {
            return Math.addExact(totalPieces, pieceCount);
        } catch (ArithmeticException exception) {
            throw new DecoderException("Outline piece count overflow", exception);
        }
    }

    private static int readCount(FriendlyByteBuf buf, int maximum, String description) {
        int count = buf.readVarInt();
        if (count < 0 || count > maximum) {
            throw new DecoderException("Invalid " + description + " count: " + count);
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void sendTo(ServerPlayer player, long sequence, List<StructureOutline> structures) {
        PacketDistributor.sendToPlayer(player, new SpyglassSnapshotPayload(
                player.level().dimension().location(), sequence, structures));
    }
}
