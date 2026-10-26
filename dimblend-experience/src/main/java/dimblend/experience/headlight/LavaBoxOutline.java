package dimblend.experience.headlight;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Wireframe of the lit-lava highlight: every lit lava block is wrapped in a 2x2x2 box centred on the block
 * (the block grown by half a block on every side), the boxes of touching or overlapping blocks are merged
 * into one volume, and only that volume's outline is drawn -- a lone block gets a plain 12-edge box, a pool
 * gets one shell around the whole pool, a flowing step or lavafall gets the stepped shape.
 *
 * <p><b>Grid.</b> The boxes' faces all lie on half-integer world coordinates, so the volume is built on a cell
 * grid shifted by half a block: cell {@code c} spans world {@code [c - 0.5, c + 0.5]} on each axis and its
 * lower corner vertex {@code c} sits at world {@code c - 0.5}. A lava block at {@code (x, y, z)} fills the
 * 2x2x2 cells {@code x..x+1, y..y+1, z..z+1}, i.e. world {@code [x - 0.5, x + 1.5]} per axis. Half-integer
 * planes cut through block centres, so no segment coincides with a block face and none can z-fight with the
 * terrain.</p>
 *
 * <p><b>Outline.</b> A unit grid edge is shared by four cells around it. It is drawn when that ring is neither
 * all empty nor all filled nor two side-by-side cells (a flat face continuing across the edge): one or three
 * filled cells are a convex or concave corner, two diagonal ones a crease. Collinear unit edges are then
 * joined, so a lone box is exactly 12 segments of length 2.</p>
 */
public final class LavaBoxOutline {
    /** Each lit block grows by this much on every side: 1 + 2 x 0.5 = the 2-block box. */
    public static final double PADDING = 0.5;

    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;

    /** Merged outline segments of the boxes around {@code surfaces}; empty in, empty out. */
    public static List<LavaEdge> edges(List<LavaSurface> surfaces) {
        if (surfaces.isEmpty()) {
            return List.of();
        }
        LongOpenHashSet cells = new LongOpenHashSet(surfaces.size() * 8);
        for (LavaSurface surface : surfaces) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        cells.add(BlockPos.asLong(surface.x() + dx, surface.y() + dy, surface.z() + dz));
                    }
                }
            }
        }
        List<LavaEdge> edges = new ArrayList<>();
        for (int axis = X; axis <= Z; axis++) {
            LongOpenHashSet drawn = drawnEdges(cells, axis);
            join(drawn, axis, edges);
        }
        return edges;
    }

    /**
     * Unit edges along {@code axis} that belong to the outline, keyed by their lower vertex. Every such edge
     * touches at least one filled cell, so walking the filled cells' own edges finds them all.
     */
    private static LongOpenHashSet drawnEdges(LongOpenHashSet cells, int axis) {
        LongOpenHashSet drawn = new LongOpenHashSet();
        LongIterator iterator = cells.iterator();
        while (iterator.hasNext()) {
            long cell = iterator.nextLong();
            int cx = BlockPos.getX(cell);
            int cy = BlockPos.getY(cell);
            int cz = BlockPos.getZ(cell);
            // The four edges of this cell that run along the axis: its lower vertex plus 0/1 on the other two.
            for (int u = 0; u <= 1; u++) {
                for (int v = 0; v <= 1; v++) {
                    long vertex = switch (axis) {
                        case X -> BlockPos.asLong(cx, cy + u, cz + v);
                        case Y -> BlockPos.asLong(cx + u, cy, cz + v);
                        default -> BlockPos.asLong(cx + u, cy + v, cz);
                    };
                    if (!drawn.contains(vertex) && isOutline(cells, vertex, axis)) {
                        drawn.add(vertex);
                    }
                }
            }
        }
        return drawn;
    }

    /** Whether the unit edge from {@code vertex} along {@code axis} is part of the volume's outline. */
    private static boolean isOutline(LongOpenHashSet cells, long vertex, int axis) {
        // The four cells around the edge, indexed by their offset (-1 or 0) on the two other axes.
        boolean lowLow = cells.contains(around(vertex, axis, -1, -1));
        boolean highLow = cells.contains(around(vertex, axis, 0, -1));
        boolean lowHigh = cells.contains(around(vertex, axis, -1, 0));
        boolean highHigh = cells.contains(around(vertex, axis, 0, 0));
        return outlineRing(lowLow, highLow, lowHigh, highHigh);
    }

    /**
     * The edge rule on the four cells around one edge, given as (first other axis, second other axis) offsets
     * low/high: drawn unless the ring is empty, full or a flat face (two cells side by side).
     */
    static boolean outlineRing(boolean lowLow, boolean highLow, boolean lowHigh, boolean highHigh) {
        int filled = (lowLow ? 1 : 0) + (highLow ? 1 : 0) + (lowHigh ? 1 : 0) + (highHigh ? 1 : 0);
        if (filled == 1 || filled == 3) {
            return true;
        }
        // Two filled: diagonal is a crease, side by side is a flat face.
        return filled == 2 && lowLow == highHigh;
    }

    private static long around(long vertex, int axis, int first, int second) {
        return switch (axis) {
            case X -> BlockPos.offset(vertex, 0, first, second);
            case Y -> BlockPos.offset(vertex, first, 0, second);
            default -> BlockPos.offset(vertex, first, second, 0);
        };
    }

    /** Joins runs of consecutive unit edges along {@code axis} into single segments. */
    private static void join(LongOpenHashSet drawn, int axis, List<LavaEdge> out) {
        int stepX = axis == X ? 1 : 0;
        int stepY = axis == Y ? 1 : 0;
        int stepZ = axis == Z ? 1 : 0;
        LongIterator iterator = drawn.iterator();
        while (iterator.hasNext()) {
            long start = iterator.nextLong();
            if (drawn.contains(BlockPos.offset(start, -stepX, -stepY, -stepZ))) {
                // Not the first edge of its run; the run is emitted from its start.
                continue;
            }
            int length = 1;
            while (drawn.contains(BlockPos.offset(start, stepX * length, stepY * length, stepZ * length))) {
                length++;
            }
            int x = BlockPos.getX(start);
            int y = BlockPos.getY(start);
            int z = BlockPos.getZ(start);
            out.add(new LavaEdge(
                    world(x), world(y), world(z),
                    world(x + stepX * length), world(y + stepY * length), world(z + stepZ * length)));
        }
    }

    /** World coordinate of grid vertex {@code index}: the grid is shifted by half a block. */
    private static double world(int index) {
        return index - PADDING;
    }

    private LavaBoxOutline() {
    }
}
