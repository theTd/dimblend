package dimblend.spyglass.structure;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks of one saved structure, decoded on the client and ready to be meshed.
 *
 * <p>Immutable and safe to read from any thread: every field is final, the arrays are private and
 * never handed out, and the biome holder is read-only after registry freeze. Stage 2 may mesh a
 * shape on a worker thread without copying or locking.
 *
 * <p>Blocks are stored as sorted linear indices relative to {@code origin} plus a palette index:
 * {@code index = (relY * sizeZ + relZ) * sizeX + relX}. Positions that are not present are
 * {@link Blocks#AIR}; a structure's pieces are mostly hollow, so a sparse sorted array is far smaller
 * than a dense one and still supports binary search per query.
 *
 * <p>{@code origin} is the min corner of the kept blocks and {@code size} their extent, both from the
 * server. They are not necessarily the outline's union bounds: only blocks inside a structure piece
 * box survive the extraction filter.
 */
public final class StructureShape {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final StructureKey key;
    private final int revision;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Optional<Holder<Biome>> biome;
    private final BlockState[] palette;
    private final long[] positions;
    private final short[] paletteIndices;

    private StructureShape(
            StructureKey key,
            int revision,
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            Optional<Holder<Biome>> biome,
            BlockState[] palette,
            long[] positions,
            short[] paletteIndices) {
        this.key = key;
        this.revision = revision;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.biome = biome;
        this.palette = palette;
        this.positions = positions;
        this.paletteIndices = paletteIndices;
    }

    /**
     * Builds an immutable shape from a decoded body.
     *
     * @param palette resolved block states, one per transmitted palette entry (takes ownership)
     * @param positions sorted linear block indices (takes ownership)
     * @param paletteIndices palette index per position (takes ownership)
     * @param biome holder resolved from the client registry; empty if this client does not know the key
     */
    public static StructureShape of(
            StructureKey key,
            int revision,
            StructureShapeCodec.DecodedShape decoded,
            BlockState[] palette,
            Optional<Holder<Biome>> biome) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(decoded, "decoded");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(biome, "biome");
        if (palette.length != decoded.paletteIds().length) {
            throw new IllegalArgumentException("Palette size does not match the decoded palette");
        }
        if (decoded.positions().length != decoded.paletteIndices().length) {
            throw new IllegalArgumentException("Block position and palette lists differ in length");
        }
        if (decoded.positions().length == 0) {
            throw new IllegalArgumentException("A shape needs at least one block");
        }
        return new StructureShape(
                key,
                revision,
                decoded.originX(),
                decoded.originY(),
                decoded.originZ(),
                decoded.sizeX(),
                decoded.sizeY(),
                decoded.sizeZ(),
                biome,
                palette,
                decoded.positions(),
                decoded.paletteIndices());
    }

    public StructureKey key() {
        return this.key;
    }

    /** Region-header revision the shape was extracted for; the newest revision wins per key. */
    public int revision() {
        return this.revision;
    }

    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    public int sizeX() {
        return this.sizeX;
    }

    public int sizeY() {
        return this.sizeY;
    }

    public int sizeZ() {
        return this.sizeZ;
    }

    /** Dominant biome across the contributing sections, resolved through the client registry. */
    public Optional<Holder<Biome>> biome() {
        return this.biome;
    }

    public int blockCount() {
        return this.positions.length;
    }

    /** Empty when no block is kept at this position, or the position is outside the shape. */
    public BlockState getBlockState(int x, int y, int z) {
        int relX = x - this.originX;
        int relY = y - this.originY;
        int relZ = z - this.originZ;
        if (relX < 0 || relX >= this.sizeX
                || relY < 0 || relY >= this.sizeY
                || relZ < 0 || relZ >= this.sizeZ) {
            return AIR;
        }
        long index = linearIndex(relX, relY, relZ);
        int found = Arrays.binarySearch(this.positions, index);
        if (found < 0) {
            return AIR;
        }
        return this.palette[this.paletteIndices[found]];
    }

    /**
     * Visits every kept block in sorted index order (Y, then Z, then X), so a mesh builder can walk
     * neighbours deterministically. Read-only, safe from worker threads.
     */
    public void forEachBlock(BlockConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int i = 0; i < this.positions.length; i++) {
            long index = this.positions[i];
            int relX = (int) (index % this.sizeX);
            long rest = index / this.sizeX;
            int relZ = (int) (rest % this.sizeZ);
            int relY = (int) (rest / this.sizeZ);
            BlockState state = this.palette[this.paletteIndices[i]];
            consumer.accept(this.originX + relX, this.originY + relY, this.originZ + relZ, state);
        }
    }

    /** The block states used by this shape, indexed by palette index. */
    public List<BlockState> palette() {
        return List.of(this.palette);
    }

    private long linearIndex(int relX, int relY, int relZ) {
        return ((long) relY * this.sizeZ + relZ) * this.sizeX + relX;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StructureShape shape)) {
            return false;
        }
        return this.revision == shape.revision
                && this.originX == shape.originX
                && this.originY == shape.originY
                && this.originZ == shape.originZ
                && this.sizeX == shape.sizeX
                && this.sizeY == shape.sizeY
                && this.sizeZ == shape.sizeZ
                && this.key.equals(shape.key)
                && this.biome.equals(shape.biome)
                && Arrays.equals(this.palette, shape.palette)
                && Arrays.equals(this.positions, shape.positions)
                && Arrays.equals(this.paletteIndices, shape.paletteIndices);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                this.key,
                this.revision,
                this.originX,
                this.originY,
                this.originZ,
                this.sizeX,
                this.sizeY,
                this.sizeZ,
                this.biome);
        result = 31 * result + Arrays.hashCode(this.palette);
        result = 31 * result + Arrays.hashCode(this.positions);
        return 31 * result + Arrays.hashCode(this.paletteIndices);
    }

    @Override
    public String toString() {
        return "StructureShape["
                + this.key
                + ", revision=" + this.revision
                + ", origin=" + this.originX + "," + this.originY + "," + this.originZ
                + ", size=" + this.sizeX + "x" + this.sizeY + "x" + this.sizeZ
                + ", blocks=" + this.positions.length
                + ", palette=" + this.palette.length
                + "]";
    }

    @FunctionalInterface
    public interface BlockConsumer {
        void accept(int x, int y, int z, BlockState state);
    }

    /** Identity helper for logs and tests. */
    public ResourceLocation id() {
        return this.key.id();
    }
}
