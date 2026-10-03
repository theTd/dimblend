package dimblend.radio.acoustics.bake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Places the probes of a radio's pathing graph from voxels, so they sit where a listener can stand
 * on every floor level (under roofs and in caves alike), not where Steam Audio's floor generator
 * would put them (the first surface below the top of its volume, which is the roof).
 * <p>
 * Only air connected to the radio counts: a flood fill through open cells within {@code radius}
 * of the radio, so sealed caves below cost no probes (sound has no path there anyway). A
 * <em>walkable</em> cell is an open cell with a solid cell below and an open cell above. The
 * horizontal plane is cut into square column cells anchored at the radio; each cell gets one probe
 * per floor level, at the walkable cell nearest the cell centre, 1.5 blocks above its floor. Too
 * many probes for the bake budget widen the column cells.
 */
public final class PathingProbePlacement {
    /** Openness of one block cell. */
    public interface Cells {
        /** {@link #OPEN}, {@link #SOLID} or {@link #UNKNOWN} (not captured, e.g. an unloaded chunk). */
        byte at(int x, int y, int z);
    }
    public static final byte OPEN = 0, SOLID = 1, UNKNOWN = 2;
    /** Column cell sizes tried in turn until the probes fit. */
    static final int[] CELL_SIZES = {4, 6, 8, 12, 16};
    /** Height of a probe above the floor it stands on: about a player's ears. */
    static final double HEAD_HEIGHT = 1.5;
    /** Walkable cells this close in height in one column cell are one floor level (stairs, slopes). */
    static final int LEVEL_SPAN = 3;

    /**
     * @param centres probe centres in world coordinates, x y z per probe
     * @param radius influence radius of each probe (the column cell size)
     * @param complete false when the flood fill reached an uncaptured cell: the region is not fully loaded
     */
    public record Probes(double[] centres, float radius, int cellSize, boolean complete) {
        public int count() { return centres.length / 3; }
    }

    /**
     * @param radio the radio's block; the flood fill starts in the open cells around it
     * @param radius blocks from the radio's centre that probes may be placed within
     * @param maxProbes upper bound on the probe count (the bake time grows with its square)
     */
    public static Probes place(Cells cells, BlockPos radio, int radius, int maxProbes) {
        Region region = flood(cells, radio, radius);
        List<int[]> walkable = walkable(cells, region);
        for (int size : CELL_SIZES) {
            double[] centres = select(walkable, radio, size);
            if (centres.length / 3 <= maxProbes || size == CELL_SIZES[CELL_SIZES.length - 1]) {
                if (centres.length / 3 > maxProbes) centres = nearest(centres, radio, maxProbes);
                return new Probes(centres, size, size, !region.reachedUnknown);
            }
        }
        throw new AssertionError("unreachable");
    }

    /** Open cells connected to the radio, as a bit set over the box radius blocks around it. */
    private static final class Region {
        final int side, originX, originY, originZ;
        final long[] bits;
        boolean reachedUnknown;

        Region(BlockPos radio, int radius) {
            side = 2 * radius + 1;
            originX = radio.getX() - radius;
            originY = radio.getY() - radius;
            originZ = radio.getZ() - radius;
            bits = new long[(int) (((long) side * side * side + 63) >> 6)];
        }

        int index(int x, int y, int z) {
            int lx = x - originX, ly = y - originY, lz = z - originZ;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= side || ly >= side || lz >= side) return -1;
            return (ly * side + lz) * side + lx;
        }

        boolean contains(int index) { return (bits[index >> 6] & (1L << index)) != 0; }

        void add(int index) { bits[index >> 6] |= 1L << index; }
    }

    private static Region flood(Cells cells, BlockPos radio, int radius) {
        Region region = new Region(radio, radius);
        double limit = (radius + 0.5) * (radius + 0.5);
        int[] queue = new int[1024];
        int head = 0, tail = 0;
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        // The radio block itself is solid; its open neighbours are where the sound starts.
        for (int[] step : steps) {
            int x = radio.getX() + step[0], y = radio.getY() + step[1], z = radio.getZ() + step[2];
            int index = region.index(x, y, z);
            if (index < 0 || region.contains(index)) continue;
            byte cell = cells.at(x, y, z);
            if (cell == UNKNOWN) region.reachedUnknown = true;
            if (cell != OPEN) continue;
            region.add(index);
            if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length * 2);
            queue[tail++] = index;
        }
        while (head < tail) {
            int index = queue[head++];
            int lx = index % region.side, lz = (index / region.side) % region.side, ly = index / (region.side * region.side);
            for (int[] step : steps) {
                int x = region.originX + lx + step[0], y = region.originY + ly + step[1], z = region.originZ + lz + step[2];
                int next = region.index(x, y, z);
                if (next < 0 || region.contains(next)) continue;
                double dx = x - radio.getX(), dy = y - radio.getY(), dz = z - radio.getZ();
                if (dx * dx + dy * dy + dz * dz > limit) continue;
                byte cell = cells.at(x, y, z);
                if (cell == UNKNOWN) region.reachedUnknown = true;
                if (cell != OPEN) continue;
                region.add(next);
                if (tail == queue.length) {
                    // Reclaim the consumed front before growing.
                    System.arraycopy(queue, head, queue, 0, tail - head);
                    tail -= head;
                    head = 0;
                    if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length * 2);
                }
                queue[tail++] = next;
            }
        }
        return region;
    }

    /** Connected open cells with a solid floor below and headroom above, as {x, y, z}. */
    private static List<int[]> walkable(Cells cells, Region region) {
        List<int[]> result = new ArrayList<>();
        int side = region.side;
        for (int ly = 0; ly < side; ly++) for (int lz = 0; lz < side; lz++) for (int lx = 0; lx < side; lx++) {
            int index = (ly * side + lz) * side + lx;
            if (!region.contains(index)) continue;
            int x = region.originX + lx, y = region.originY + ly, z = region.originZ + lz;
            if (cells.at(x, y - 1, z) != SOLID) continue;
            int above = region.index(x, y + 1, z);
            boolean headroom = above >= 0 ? region.contains(above) : cells.at(x, y + 1, z) == OPEN;
            if (headroom) result.add(new int[] {x, y, z});
        }
        return result;
    }

    /** One probe per column cell and floor level; ordered by column cell, then height. */
    private static double[] select(List<int[]> walkable, BlockPos radio, int size) {
        // Column cell key -> walkable cells in it, sorted by height.
        var columns = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<int[]>>();
        for (int[] cell : walkable) {
            long key = columnKey(Math.floorDiv(cell[0] - radio.getX(), size), Math.floorDiv(cell[2] - radio.getZ(), size));
            columns.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cell);
        }
        long[] keys = columns.keySet().toLongArray();
        Arrays.sort(keys);
        double[] centres = new double[walkable.size() * 3];
        int count = 0;
        double half = (size - 1) / 2.0;
        for (long key : keys) {
            List<int[]> cells = columns.get(key);
            cells.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1])
                    : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[2], b[2]));
            double centreX = radio.getX() + columnX(key) * size + half, centreZ = radio.getZ() + columnZ(key) * size + half;
            int start = 0;
            while (start < cells.size()) {
                int levelY = cells.get(start)[1];
                int[] best = null;
                double bestDistance = Double.POSITIVE_INFINITY;
                int end = start;
                while (end < cells.size() && cells.get(end)[1] < levelY + LEVEL_SPAN) {
                    int[] cell = cells.get(end++);
                    double dx = cell[0] - centreX, dz = cell[2] - centreZ;
                    double distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        best = cell;
                        bestDistance = distance;
                    }
                }
                centres[count * 3] = best[0] + 0.5;
                centres[count * 3 + 1] = best[1] + HEAD_HEIGHT;
                centres[count * 3 + 2] = best[2] + 0.5;
                count++;
                start = end;
            }
        }
        return Arrays.copyOf(centres, count * 3);
    }

    /** The {@code limit} probes nearest the radio, in their original order. */
    private static double[] nearest(double[] centres, BlockPos radio, int limit) {
        int count = centres.length / 3;
        Integer[] order = new Integer[count];
        double[] distances = new double[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
            double dx = centres[i * 3] - radio.getX() - 0.5, dy = centres[i * 3 + 1] - radio.getY() - 0.5, dz = centres[i * 3 + 2] - radio.getZ() - 0.5;
            distances[i] = dx * dx + dy * dy + dz * dz;
        }
        Arrays.sort(order, (a, b) -> Double.compare(distances[a], distances[b]));
        Integer[] kept = Arrays.copyOf(order, limit);
        Arrays.sort(kept);
        double[] result = new double[limit * 3];
        for (int i = 0; i < limit; i++) System.arraycopy(centres, kept[i] * 3, result, i * 3, 3);
        return result;
    }

    private static long columnKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private static int columnX(long key) { return (int) (key >> 32); }

    private static int columnZ(long key) { return (int) key; }

    private PathingProbePlacement() { }
}
