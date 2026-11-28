package dimblend.mirage;

/**
 * Shared geometry of the mirage tableau: how big the cap is, how far away it is activated, and
 * which chunks of the {@code (slabX, side)} rectangle it actually contains.
 *
 * <p><b>Endpoint-agnostic on purpose.</b> The server ({@link MirageServerManager}) must generate
 * exactly the chunk set the client ({@code ClientMirageManager}) asked for and the client
 * completeness check must agree with both, so this lives in {@code dimblend.mirage} and imports
 * nothing from {@code dimblend.client}.</p>
 *
 * <p><b>Why an ellipse instead of a rectangle.</b> A fixed rectangle either pokes its corners into
 * the fog at oblique angles (visible as a straight cut) or is too narrow at the centre of the
 * seam. The cap is an ellipse in {@code (depth past the seam, |lateral Z|)} space: the full
 * {@code ±halfWidthZ} right on the seam (where the player looks through it), the full
 * {@code depthX} straight ahead, and the invisible corners trimmed. Both the server's target set
 * and the client's mesh share the same predicate, so a corner that is missing is never counted as
 * a missing chunk.</p>
 *
 * <p><b>Why the radius tracks the render distance.</b> The seam chord a 12-chunk render distance
 * can actually see is ±192 blocks; a fixed 224-block activation radius (and a fixed 12-chunk cap)
 * left the far band's {@code Y=128} wall popping in at 177..205 blocks. Everything here is derived
 * from {@code rdChunks}, so a player who lowers the render distance also stops paying for the
 * tableau blocks they can no longer see.</p>
 */
public final class MirageCapGeometry {
    /** Deepest tableau allowed, in chunks past the seam (192 blocks at 12). */
    public static final int MAX_DEPTH_X = 12;
    /** Widest tableau half width allowed, in chunks along Z (±192 blocks at 12). */
    public static final int MAX_HALF_WIDTH_Z = 12;
    /** Shallowest tableau: below two chunks the seam would show its own edge. */
    public static final int MIN_DEPTH_X = 2;
    /** Narrowest tableau: below four chunks the visible seam chord would be cut off. */
    public static final int MIN_HALF_WIDTH_Z = 4;
    /** Server/club render distance range this geometry is designed for, in chunks. */
    public static final int MIN_RENDER_DISTANCE_CHUNKS = 6;
    public static final int MAX_RENDER_DISTANCE_CHUNKS = 16;
    /** Fallback render distance when the client option is unavailable. */
    public static final int DEFAULT_RENDER_DISTANCE_CHUNKS = 12;
    /** Blocks of slack beyond the render distance before the mask activates. */
    public static final int ACTIVATION_SLACK_BLOCKS = 32;
    /** Blocks of lead time between the request radius and the activation radius. */
    public static final int REQUEST_LEAD_BLOCKS = 48;
    /** Blocks of hysteresis before a seam's whole cache is dropped. */
    public static final int DROP_LEAD_BLOCKS = 48;

    private MirageCapGeometry() {
    }

    /** Clamps a raw {@code options.renderDistance()} to the range the cap math is tuned for. */
    public static int clampRenderDistanceChunks(int rawChunks) {
        if (rawChunks <= 0) {
            return DEFAULT_RENDER_DISTANCE_CHUNKS;
        }
        return Math.max(MIN_RENDER_DISTANCE_CHUNKS, Math.min(MAX_RENDER_DISTANCE_CHUNKS, rawChunks));
    }

    /** Blocks from the seam where the S2 masks switch on: {@code rd * 16 + 32} (224 at RD 12). */
    public static int seamActivationRadius(int rdChunks) {
        return clampRenderDistanceChunks(rdChunks) * 16 + ACTIVATION_SLACK_BLOCKS;
    }

    /**
     * Blocks within which a seam is asked for chunks: 48 blocks <em>before</em> S2 activates, so
     * the tableau is already baked when the mask switches on instead of popping in.
     */
    public static int requestRadius(int rdChunks) {
        return seamActivationRadius(rdChunks) + REQUEST_LEAD_BLOCKS;
    }

    /** Blocks past which a seam's cached chunks are released. */
    public static int dropRadius(int rdChunks) {
        return requestRadius(rdChunks) + DROP_LEAD_BLOCKS;
    }

    /** Tableau footprint in chunks: {@code depthX} past the seam, {@code halfWidthZ} either way. */
    public record CapShape(int depthX, int halfWidthZ) {
        public CapShape {
            if (depthX <= 0 || halfWidthZ < 0) {
                throw new IllegalArgumentException("bad cap shape " + depthX + "x" + halfWidthZ);
            }
        }
    }

    /**
     * Cap footprint for the current camera distance to the seam.
     *
     * <p>Distance is quantised to two-chunk buckets before it is used, so walking at 5 m/s does
     * not re-request a chunk every frame: the shape changes at most once per 32 blocks.</p>
     *
     * <p>The shape grows monotonically as the camera approaches (depth from
     * {@code rd - dist + 2} up to {@link #MAX_DEPTH_X}, chord from
     * {@code sqrt((rd+2)^2 - dist^2)}), which is what lets the client only re-request when the new
     * shape is <em>larger</em> than what it already has: backing away is free.</p>
     */
    public static CapShape capShape(double seamDistanceBlocks, int rdChunks) {
        int rd = clampRenderDistanceChunks(rdChunks);
        double distance = Math.max(0.0, seamDistanceBlocks);
        int distChunks = Math.max(0, ((int) Math.floor(distance / 16.0)) & ~1);
        int depthX = clamp(rd - distChunks + 2, MIN_DEPTH_X, MAX_DEPTH_X);
        int radius = rd + 2;
        int chord = distChunks >= radius ? MIN_HALF_WIDTH_Z
                : (int) Math.ceil(Math.sqrt((double) (radius * radius - distChunks * distChunks)));
        int halfWidthZ = clamp(chord, MIN_HALF_WIDTH_Z, MAX_HALF_WIDTH_Z);
        return new CapShape(depthX, halfWidthZ);
    }

    /**
     * First chunk X of the cap's bounding box, inclusive: the seam chunk for side +1, the first
     * chunk west of it for side -1.
     */
    public static int firstChunkX(int slabX, byte side, int depthX) {
        int seamChunkX = slabX >> 4;
        return side == MirageChunkKey.WEST_BAND_INTO_EAST ? seamChunkX : seamChunkX - depthX;
    }

    /** Last chunk X of the cap's bounding box, inclusive. */
    public static int lastChunkX(int slabX, byte side, int depthX) {
        int seamChunkX = slabX >> 4;
        return side == MirageChunkKey.WEST_BAND_INTO_EAST ? seamChunkX + depthX - 1 : seamChunkX - 1;
    }

    /**
     * True when {@code (chunkX, chunkZ)} lies inside the elliptical cap of {@code (slabX, side)}.
     *
     * <p>Chunk 0 of the cap is the first chunk past the seam ({@code slabX >> 4} for side +1,
     * {@code slabX >> 4 - 1} for side -1), and the normalised edge is the unit circle with a small
     * 5% overshoot so the pixel-exact boundary chunk is not dropped by rounding.</p>
     */
    public static boolean inCap(int slabX, byte side, int centerChunkZ,
                                int depthX, int halfWidthZ, int chunkX, int chunkZ) {
        if (depthX <= 0 || halfWidthZ < 0) {
            return false;
        }
        int seamChunkX = slabX >> 4;
        int dx = side == MirageChunkKey.WEST_BAND_INTO_EAST
                ? chunkX - seamChunkX : seamChunkX - 1 - chunkX;
        int dz = Math.abs(chunkZ - centerChunkZ);
        if (dx < 0 || dx >= depthX || dz > halfWidthZ) {
            return false;
        }
        double nx = dx / (depthX + 0.5);
        double nz = dz / (halfWidthZ + 0.5);
        return nx * nx + nz * nz <= 1.05;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
