package dimblend.mirage;

/**
 * One in-memory mirage chunk: which band's delegate generates it, and where it is placed.
 *
 * <p>Coordinate contract (Unit 2's client must match exactly):</p>
 * <ul>
 *   <li>{@code slabX} — the seam block X ({@code k * bandSize}, e.g. {@code 4096}).</li>
 *   <li>{@code side = +1} — generate <b>west band A</b> ({@code region = slabX / bandSize - 1})
 *       with delegate A into <b>east columns</b> {@code chunkX >= slabX >> 4}: the far side seen
 *       while standing on A looking at B, and the sliver of A that pokes into B's bubble when
 *       looking back.</li>
 *   <li>{@code side = -1} — generate <b>east band B</b> ({@code region = slabX / bandSize})
 *       with delegate B into <b>west columns</b> {@code chunkX < slabX >> 4}.</li>
 * </ul>
 *
 * <p>A mirage chunk is a virtual copy: it never enters {@code ChunkMap}, is never written to
 * disk, and is rendered by the client's static mesh pass, not by real terrain.</p>
 */
public record MirageChunkKey(int slabX, byte side, int chunkX, int chunkZ) {
    public static final byte WEST_BAND_INTO_EAST = 1;
    public static final byte EAST_BAND_INTO_WEST = -1;

    public MirageChunkKey {
        if (side != WEST_BAND_INTO_EAST && side != EAST_BAND_INTO_WEST) {
            throw new IllegalArgumentException("mirage side must be +1 or -1: " + side);
        }
    }

    /** Seam chunk X: the first chunk east of/at the seam for side +1, west of it for side -1. */
    public int seamChunkX() {
        return this.slabX >> 4;
    }

    /** True when this chunk lies on the target side of the seam. */
    public boolean isOnTargetSide() {
        return this.side == WEST_BAND_INTO_EAST
                ? this.chunkX >= this.seamChunkX()
                : this.chunkX < this.seamChunkX();
    }
}
