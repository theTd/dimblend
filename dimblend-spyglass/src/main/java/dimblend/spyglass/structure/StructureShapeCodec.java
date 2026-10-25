package dimblend.spyglass.structure;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wire format of one structure shape body (palette + kept block positions), shared by the server
 * encoder and the client decoder.
 *
 * <p>The blocks are the largest part of a shape, so positions are stored as linear indices relative
 * to the origin ({@code (relY * sizeZ + relZ) * sizeX + relX}), sorted and delta encoded as var-longs
 * (the linear index space can exceed an {@code int}: up to 2^20 per axis), with a palette index per
 * block. A palette of block-state ids is used because a state name costs 10-40 bytes while an id
 * costs 1-3. The ids agree on both sides: NeoForge syncs the block registry to the client
 * ({@code NeoForgeRegistriesSetup.VANILLA_SYNC_REGISTRIES} lists {@code BuiltInRegistries.BLOCK} and
 * {@code modifyRegistries} calls {@code setSync(true)} on it) and rebuilds the block-state id map
 * after the sync, which is what vanilla's own chunk packets rely on for their paletted containers.
 *
 * <p>Decoding is bounded and strict: palette size, block count, axis sizes, index range, palette
 * range, duplicate/unsorted indices and trailing bytes all raise {@link DecoderException}.
 */
public final class StructureShapeCodec {
    /** Kept-block cap per structure on both sides. */
    public static final int MAX_BLOCKS = 262_144;
    /** Distinct block states per shape; far above any real structure. */
    public static final int MAX_PALETTE_ENTRIES = 16_384;
    /** Largest supported shape extent on one axis; keeps relative indices inside a {@code long}. */
    public static final int MAX_AXIS = 1 << 20;
    /** Largest encoded body; the shape payload splits bodies of at most this size into parts. */
    public static final int MAX_BODY_BYTES = 4 << 20;

    private StructureShapeCodec() {
    }

    /** One kept block: its linear index relative to the origin and the block state. */
    public record BlockEntry(long index, BlockState state) {
        public BlockEntry {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A decoded shape body, with palette ids not yet resolved to block states. */
    public record DecodedShape(
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            ResourceLocation biome,
            int[] paletteIds,
            long[] positions,
            short[] paletteIndices) {
        public DecodedShape {
            Objects.requireNonNull(biome, "biome");
            if (paletteIds.length == 0) {
                throw new IllegalArgumentException("Empty palette");
            }
            if (positions.length != paletteIndices.length || positions.length == 0) {
                throw new IllegalArgumentException("Block lists are empty or differ in length");
            }
        }

        /** Number of kept blocks. */
        public int blockCount() {
            return this.positions.length;
        }
    }

    /**
     * Encodes one shape body. The block list may arrive in any order; it is sorted and duplicates are
     * rejected.
     *
     * @throws IllegalArgumentException if the shape exceeds the codec's caps
     */
    public static byte[] encode(
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            ResourceLocation biome,
            List<BlockEntry> blocks) {
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(blocks, "blocks");
        requireAxis(sizeX, "sizeX");
        requireAxis(sizeY, "sizeY");
        requireAxis(sizeZ, "sizeZ");
        requireRange(originX, "originX");
        requireRange(originY, "originY");
        requireRange(originZ, "originZ");
        requireExtent(originX, sizeX, "X");
        requireExtent(originY, sizeY, "Y");
        requireExtent(originZ, sizeZ, "Z");
        long product = (long) sizeX * sizeY * sizeZ;

        List<BlockEntry> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingLong(BlockEntry::index));
        if (sorted.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("Too many blocks: " + sorted.size());
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("A shape needs at least one block");
        }

        Map<BlockState, Integer> palette = new HashMap<>();
        List<Integer> paletteIds = new ArrayList<>();
        long previous = -1L;
        ByteBuf buffer = Unpooled.buffer();
        try {
            FriendlyByteBuf out = new FriendlyByteBuf(buffer);
            // Blocks are encoded into a scratch buffer first: the palette has to be written before
            // the blocks (the reader needs it to interpret palette indices), but its size is only
            // known after walking them.
            ByteBuf blockBytes = Unpooled.buffer();
            try {
                FriendlyByteBuf blockOut = new FriendlyByteBuf(blockBytes);
                for (BlockEntry entry : sorted) {
                    long index = entry.index();
                    if (index < 0L || index >= product) {
                        throw new IllegalArgumentException("Block index " + index + " outside shape " + product);
                    }
                    if (index <= previous) {
                        throw new IllegalArgumentException("Duplicate or unsorted block index " + index);
                    }
                    Integer paletteIndex = palette.get(entry.state());
                    if (paletteIndex == null) {
                        if (palette.size() >= MAX_PALETTE_ENTRIES) {
                            throw new IllegalArgumentException("Too many block states: " + (palette.size() + 1));
                        }
                        paletteIndex = palette.size();
                        palette.put(entry.state(), paletteIndex);
                        paletteIds.add(Block.getId(entry.state()));
                    }
                    blockOut.writeVarLong(index - previous);
                    blockOut.writeVarInt(paletteIndex);
                    previous = index;
                }
                byte[] payload = new byte[blockBytes.readableBytes()];
                blockBytes.readBytes(payload);

                out.writeInt(originX);
                out.writeInt(originY);
                out.writeInt(originZ);
                out.writeVarInt(sizeX);
                out.writeVarInt(sizeY);
                out.writeVarInt(sizeZ);
                out.writeResourceLocation(biome);
                out.writeVarInt(paletteIds.size());
                for (int paletteId : paletteIds) {
                    if (paletteId < 0) {
                        throw new IllegalArgumentException("Negative block state id " + paletteId);
                    }
                    out.writeVarInt(paletteId);
                }
                out.writeVarInt(sorted.size());
                out.writeBytes(payload);
            } finally {
                blockBytes.release();
            }

            byte[] body = new byte[buffer.readableBytes()];
            buffer.readBytes(body);
            if (body.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("Encoded shape body too large: " + body.length);
            }
            return body;
        } finally {
            buffer.release();
        }
    }

    /**
     * Decodes and validates a shape body. Palette ids are returned raw; resolving them through
     * {@link Block#stateById} happens on the client so this method stays registry-independent.
     */
    public static DecodedShape decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new DecoderException("Invalid shape body length: " + body.length);
        }
        ByteBuf buffer = Unpooled.wrappedBuffer(body);
        try {
            FriendlyByteBuf in = new FriendlyByteBuf(buffer);
            int originX = in.readInt();
            int originY = in.readInt();
            int originZ = in.readInt();
            int sizeX = readAxis(in, "sizeX");
            int sizeY = readAxis(in, "sizeY");
            int sizeZ = readAxis(in, "sizeZ");
            requireDecodedRange(originX, sizeX, "X");
            requireDecodedRange(originY, sizeY, "Y");
            requireDecodedRange(originZ, sizeZ, "Z");
            long product = (long) sizeX * sizeY * sizeZ;

            ResourceLocation biome;
            try {
                biome = in.readResourceLocation();
            } catch (RuntimeException exception) {
                throw new DecoderException("Invalid biome key in shape body", exception);
            }

            int paletteCount = readCount(in, MAX_PALETTE_ENTRIES, "palette");
            if (paletteCount == 0) {
                throw new DecoderException("Empty shape palette");
            }
            int[] paletteIds = new int[paletteCount];
            for (int i = 0; i < paletteCount; i++) {
                paletteIds[i] = in.readVarInt();
                if (paletteIds[i] < 0) {
                    throw new DecoderException("Negative block state id: " + paletteIds[i]);
                }
            }

            int blockCount = readCount(in, MAX_BLOCKS, "blocks");
            if (blockCount == 0) {
                throw new DecoderException("Empty shape block list");
            }
            long[] positions = new long[blockCount];
            short[] paletteIndices = new short[blockCount];
            long previous = -1L;
            for (int i = 0; i < blockCount; i++) {
                long delta = in.readVarLong();
                long index = previous + delta;
                if (delta <= 0L || index < previous || index < 0L || index >= product) {
                    throw new DecoderException("Invalid block index delta " + delta + " at " + i);
                }
                int paletteIndex = in.readVarInt();
                if (paletteIndex < 0 || paletteIndex >= paletteCount) {
                    throw new DecoderException(
                            "Palette index " + paletteIndex + " outside [0, " + paletteCount + ")");
                }
                positions[i] = index;
                paletteIndices[i] = (short) paletteIndex;
                previous = index;
            }
            if (in.isReadable()) {
                throw new DecoderException("Trailing bytes in shape body: " + in.readableBytes());
            }
            return new DecodedShape(
                    originX,
                    originY,
                    originZ,
                    sizeX,
                    sizeY,
                    sizeZ,
                    biome,
                    paletteIds,
                    positions,
                    paletteIndices);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Malformed structure shape body", exception);
        } finally {
            buffer.release();
        }
    }

    private static int readAxis(FriendlyByteBuf in, String name) {
        int axis = in.readVarInt();
        if (axis <= 0 || axis > MAX_AXIS) {
            throw new DecoderException("Invalid " + name + ": " + axis);
        }
        return axis;
    }

    private static int readCount(FriendlyByteBuf in, int maximum, String description) {
        int count = in.readVarInt();
        if (count < 0 || count > maximum) {
            throw new DecoderException("Invalid " + description + " count: " + count);
        }
        return count;
    }

    private static void requireDecodedRange(int origin, int size, String axis) {
        if (origin < -OutlineBox.MAX_COORDINATE || origin > OutlineBox.MAX_COORDINATE) {
            throw new DecoderException("Shape origin outside the world on " + axis + ": " + origin);
        }
        long last = (long) origin + size - 1L;
        if (last < -OutlineBox.MAX_COORDINATE || last > OutlineBox.MAX_COORDINATE) {
            throw new DecoderException("Shape extent outside the world on " + axis + ": " + last);
        }
    }

    private static void requireAxis(int axis, String name) {
        if (axis <= 0 || axis > MAX_AXIS) {
            throw new IllegalArgumentException("Invalid " + name + ": " + axis);
        }
    }

    private static void requireExtent(int origin, int size, String axis) {
        long last = (long) origin + size - 1L;
        if (last < -OutlineBox.MAX_COORDINATE || last > OutlineBox.MAX_COORDINATE) {
            throw new IllegalArgumentException("Shape extent outside the world on " + axis + ": " + last);
        }
    }

    private static void requireRange(int origin, String name) {
        if (origin < -OutlineBox.MAX_COORDINATE || origin > OutlineBox.MAX_COORDINATE) {
            throw new IllegalArgumentException("Invalid " + name + ": " + origin);
        }
    }
}
