package dimblend.mirage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server → client: one encoded mirage chunk.
 *
 * <p>{@code laneName} is the band the chunk belongs to (its far sky/fog identity);
 * {@code otherLaneName} is the band the player is standing in (the near side of the same seam),
 * so the client can set up both sides without waiting for {@link BandSeamInfoPayload}.</p>
 *
 * <p>Wire order (matched by the client decoder): key (slabX varint, side byte, chunkX varint,
 * chunkZ varint), delegateIndex varint, laneName UTF-8, otherLaneName UTF-8, minSectionY varint,
 * sectionCount varint, sections byte[] (see {@link MirageSectionCodec}), heightmap count varint +
 * shorts, emissive count varint + varints.</p>
 *
 * <p>The handler is a registerable sink ({@link MirageClientReceiver}) so this class never
 * imports {@code dimblend.client}: a dedicated server can load and register the payload without
 * touching client implementation classes.</p>
 */
public record MirageChunkPayload(
        MirageChunkKey key,
        int delegateIndex,
        String laneName,
        String otherLaneName,
        int minSectionY,
        int sectionCount,
        byte[] sections,
        short[] heightmap,
        int[] emissive
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MirageChunkPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dimblend", "mirage_chunk"));

    /** Refuse absurd allocations from a malformed peer before the buffer grows. */
    private static final int MAX_SECTIONS_BYTES = 1 << 21;
    private static final int MAX_EMISSIVE = 1 << 16;

    public static final StreamCodec<RegistryFriendlyByteBuf, MirageChunkPayload> STREAM_CODEC =
            StreamCodec.of(MirageChunkPayload::write, MirageChunkPayload::read);

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, MirageChunkPayload::handle);
    }

    @Override
    public CustomPacketPayload.Type<MirageChunkPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, MirageChunkPayload payload) {
        MirageChunkKey key = payload.key();
        buf.writeVarInt(key.slabX());
        buf.writeByte(key.side());
        buf.writeVarInt(key.chunkX());
        buf.writeVarInt(key.chunkZ());
        buf.writeVarInt(payload.delegateIndex());
        buf.writeUtf(payload.laneName());
        buf.writeUtf(payload.otherLaneName());
        buf.writeVarInt(payload.minSectionY());
        buf.writeVarInt(payload.sectionCount());
        buf.writeByteArray(payload.sections());
        short[] heightmap = payload.heightmap();
        buf.writeVarInt(heightmap.length);
        for (short height : heightmap) {
            buf.writeShort(height);
        }
        int[] emissive = payload.emissive();
        buf.writeVarInt(emissive.length);
        for (int packed : emissive) {
            buf.writeVarInt(packed);
        }
    }

    private static MirageChunkPayload read(RegistryFriendlyByteBuf buf) {
        MirageChunkKey key = new MirageChunkKey(
                buf.readVarInt(), buf.readByte(), buf.readVarInt(), buf.readVarInt());
        int delegateIndex = buf.readVarInt();
        String laneName = buf.readUtf();
        String otherLaneName = buf.readUtf();
        int minSectionY = buf.readVarInt();
        int sectionCount = buf.readVarInt();
        byte[] sections = buf.readByteArray(MAX_SECTIONS_BYTES);
        int heightmapLength = buf.readVarInt();
        if (heightmapLength < 0 || heightmapLength > MirageSectionCodec.HEIGHTMAP_ENTRIES) {
            throw new IllegalArgumentException("mirage heightmap length " + heightmapLength);
        }
        short[] heightmap = new short[heightmapLength];
        for (int i = 0; i < heightmapLength; i++) {
            heightmap[i] = buf.readShort();
        }
        int emissiveLength = buf.readVarInt();
        if (emissiveLength < 0 || emissiveLength > MAX_EMISSIVE) {
            throw new IllegalArgumentException("mirage emissive length " + emissiveLength);
        }
        int[] emissive = new int[emissiveLength];
        for (int i = 0; i < emissiveLength; i++) {
            emissive[i] = buf.readVarInt();
        }
        return new MirageChunkPayload(key, delegateIndex, laneName, otherLaneName,
                minSectionY, sectionCount, sections, heightmap, emissive);
    }

    private static void handle(MirageChunkPayload payload, IPayloadContext context) {
        // PayloadRegistrar already wraps handlers onto the main thread.
        MirageClientReceiver.accept(payload);
    }
}
