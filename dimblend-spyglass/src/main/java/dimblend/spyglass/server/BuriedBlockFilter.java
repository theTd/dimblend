package dimblend.spyglass.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drops the blocks of a structure shape that are buried under terrain, so the client only meshes
 * what can actually be seen from open air.
 *
 * <p>The shape extraction already keeps every generated block inside a piece box; this filter then
 * answers, per block, "could light reach it from outside?":
 *
 * <ul>
 *   <li>A cell at or above the column surface {@code H(x, z)} is open sky and seeds the search.
 *       {@code H} is the saved chunk's {@code WORLD_SURFACE} heightmap (see {@link ChunkHeightmaps}),
 *       so for a structure that sticks out of the ground its roof is below {@code H} and the cells
 *       above it seed the search.</li>
 *   <li>Below {@code H}, inside a piece box, a cell is <b>passable</b> when it holds no kept block or
 *       its kept block is non-occluding ({@link BlockState#canOcclude()}), e.g. leaves or glass.
 *       Outside every piece box it is not passable: that space is assumed to be terrain.</li>
 *   <li>The search is a multi-source breadth-first search <b>per Y level</b> from all seeds, moving
 *       horizontally only (4 neighbours) through passable cells, at most {@link #MAX_STEPS} steps
 *       from a seed. Open-sky cells are not stored — they are the implicit sources — so the search
 *       only keeps passable interior cells, which keeps its memory proportional to the reachable
 *       interior air instead of the whole (possibly huge) volume. Eight steps is roughly "the visible
 *       face of a room": a wall under a one-block roof overhang is one step from the open column next
 *       to it, while the middle of a closed interior floor is out of reach.</li>
 *   <li>A kept block survives when its own cell was reached (open sky or passable and reached) or
 *       any of its six neighbours was reached, so the surface layer of a wall or floor is kept and
 *       the completely enclosed interior is not.</li>
 * </ul>
 *
 * <p>The searched volume is the kept-block extent grown by one block on every side. Its cell count
 * is bounded by the extraction's visited-cell budget: a structure whose extent alone is larger than
 * that is reported as {@link Result#volumeTooLarge() volume too large}, and the extractor must skip
 * it rather than ship unfiltered blocks.
 *
 * <p>Pure: no game state is read, the surface and piece membership are passed in. Unit tested.
 */
final class BuriedBlockFilter {
    /** Open air reaches this many horizontal moves into a covered interior. */
    static final int MAX_STEPS = 8;
    /** The searched volume grows one block past the kept blocks, so every block's neighbours exist. */
    static final int VOLUME_MARGIN = 1;
    /** Cancellation is checked at least this often while seeding and while expanding the search. */
    private static final int CANCELLATION_INTERVAL = 1024;

    private static final int[] X_OFFSETS = {-1, 1, 0, 0};
    private static final int[] Z_OFFSETS = {0, 0, -1, 1};
    private static final int[] NEIGHBOUR_X = {-1, 1, 0, 0, 0, 0};
    private static final int[] NEIGHBOUR_Y = {0, 0, -1, 1, 0, 0};
    private static final int[] NEIGHBOUR_Z = {0, 0, 0, 0, -1, 1};

    private BuriedBlockFilter() {
    }

    /** The column surface used to decide what is open sky. */
    @FunctionalInterface
    interface ColumnSurface {
        /**
         * @return first Y above the column's topmost non-air block, or {@code Integer.MAX_VALUE}
         *         when the column's surface is unknown (it is then closed to open air)
         */
        int height(int x, int z);
    }

    /** The piece boxes of the structure being filtered. */
    @FunctionalInterface
    interface PieceMembership {
        boolean contains(int x, int y, int z);
    }

    /**
     * @param blocks          the surviving blocks; empty when all of them are buried
     * @param volumeCells     number of cells the search was bounded to
     * @param volumeTooLarge  the volume exceeded the caller's budget, so nothing was filtered; the
     *                        caller must not use the input blocks
     * @param aborted         the cancellation check fired; neither result nor stats are meaningful
     */
    record Result(
            Long2ObjectOpenHashMap<BlockState> blocks,
            long volumeCells,
            boolean volumeTooLarge,
            boolean aborted) {
    }

    /**
     * @param blocks         the kept blocks of one structure, keyed by {@link BlockPos#asLong}
     * @param surface        column surface, from the covering chunks and their one-chunk ring
     * @param pieces         piece-box membership
     * @param maxVolumeCells the extraction's visited-cell budget
     * @param cancelled      checked periodically; a true value aborts without a result
     */
    static Result filter(
            Long2ObjectOpenHashMap<BlockState> blocks,
            ColumnSurface surface,
            PieceMembership pieces,
            long maxVolumeCells,
            BooleanSupplier cancelled) {
        if (blocks.isEmpty()) {
            return new Result(new Long2ObjectOpenHashMap<>(), 0L, false, false);
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long position : blocks.keySet()) {
            int x = BlockPos.getX(position);
            int y = BlockPos.getY(position);
            int z = BlockPos.getZ(position);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        // The blocks were validated to be inside packable world coordinates, so the margin cannot
        // overflow; clamp anyway so this class stays total for arbitrary input.
        minX = clampSubtract(minX, VOLUME_MARGIN);
        minY = clampSubtract(minY, VOLUME_MARGIN);
        minZ = clampSubtract(minZ, VOLUME_MARGIN);
        maxX = clampAdd(maxX, VOLUME_MARGIN);
        maxY = clampAdd(maxY, VOLUME_MARGIN);
        maxZ = clampAdd(maxZ, VOLUME_MARGIN);

        long volumeCells;
        try {
            long width = (long) maxX - minX + 1L;
            long height = (long) maxY - minY + 1L;
            long depth = (long) maxZ - minZ + 1L;
            volumeCells = Math.multiplyExact(Math.multiplyExact(width, height), depth);
        } catch (ArithmeticException overflow) {
            return new Result(new Long2ObjectOpenHashMap<>(), Long.MAX_VALUE, true, false);
        }
        if (volumeCells > maxVolumeCells) {
            return new Result(new Long2ObjectOpenHashMap<>(), volumeCells, true, false);
        }
        if (cancelled.getAsBoolean()) {
            return new Result(new Long2ObjectOpenHashMap<>(), volumeCells, false, true);
        }

        // Only passable cells are stored: open-sky seed cells are implicit, and a cell inside a piece
        // box can only be passable below its surface. This keeps the search's memory proportional to
        // the reachable interior air instead of the whole (possibly huge) volume.
        LongOpenHashSet reached = new LongOpenHashSet();
        LongArrayList frontier = new LongArrayList();
        int sinceCancellationCheck = 0;
        // Step 1: passable cells with an open-sky horizontal neighbour. A cell at y >= H is a seed;
        // the search may move into a passable neighbour one horizontal step away.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (++sinceCancellationCheck >= CANCELLATION_INTERVAL) {
                    sinceCancellationCheck = 0;
                    if (cancelled.getAsBoolean()) {
                        return new Result(new Long2ObjectOpenHashMap<>(), volumeCells, false, true);
                    }
                }
                int firstOpenY = surface.height(x, z);
                if (firstOpenY <= minY) {
                    continue;
                }
                int lastCoveredY = Math.min(maxY, firstOpenY - 1);
                for (int y = minY; y <= lastCoveredY; y++) {
                    if (!isPassable(blocks, surface, pieces, x, y, z)
                            || !hasOpenSkyNeighbour(surface, x, y, z)) {
                        continue;
                    }
                    long position = BlockPos.asLong(x, y, z);
                    if (reached.add(position)) {
                        frontier.add(position);
                    }
                }
            }
        }

        // Steps 2..MAX_STEPS: horizontal 4-neighbour moves through passable cells.
        for (int step = 1; step < MAX_STEPS && !frontier.isEmpty(); step++) {
            LongArrayList next = new LongArrayList();
            for (int index = 0; index < frontier.size(); index++) {
                if (++sinceCancellationCheck >= CANCELLATION_INTERVAL) {
                    sinceCancellationCheck = 0;
                    if (cancelled.getAsBoolean()) {
                        return new Result(new Long2ObjectOpenHashMap<>(), volumeCells, false, true);
                    }
                }
                long position = frontier.getLong(index);
                int x = BlockPos.getX(position);
                int y = BlockPos.getY(position);
                int z = BlockPos.getZ(position);
                for (int direction = 0; direction < 4; direction++) {
                    int nextX = x + X_OFFSETS[direction];
                    int nextZ = z + Z_OFFSETS[direction];
                    if (nextX < minX || nextX > maxX || nextZ < minZ || nextZ > maxZ) {
                        continue;
                    }
                    if (isPassable(blocks, surface, pieces, nextX, y, nextZ)) {
                        long nextPosition = BlockPos.asLong(nextX, y, nextZ);
                        if (reached.add(nextPosition)) {
                            next.add(nextPosition);
                        }
                    }
                }
            }
            frontier = next;
        }

        Long2ObjectOpenHashMap<BlockState> visible = new Long2ObjectOpenHashMap<>(blocks.size());
        for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
            long position = entry.getLongKey();
            if (isReached(reached, surface, position)) {
                visible.put(position, entry.getValue());
            }
        }
        return new Result(visible, volumeCells, false, false);
    }

    /**
     * A cell is reached for the purpose of keeping blocks either because it is open sky (at or above
     * the column surface) or because the search reached it as a passable cell.
     */
    private static boolean isReached(LongOpenHashSet reached, ColumnSurface surface, long position) {
        int x = BlockPos.getX(position);
        int y = BlockPos.getY(position);
        int z = BlockPos.getZ(position);
        if (surface.height(x, z) <= y) {
            return true;
        }
        if (reached.contains(position)) {
            return true;
        }
        for (int i = 0; i < NEIGHBOUR_X.length; i++) {
            int neighbourX = x + NEIGHBOUR_X[i];
            int neighbourY = y + NEIGHBOUR_Y[i];
            int neighbourZ = z + NEIGHBOUR_Z[i];
            if (surface.height(neighbourX, neighbourZ) <= neighbourY) {
                return true;
            }
            if (reached.contains(BlockPos.asLong(neighbourX, neighbourY, neighbourZ))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Below the surface, inside a piece box and without an occluding kept block. Cells at or above
     * the surface are seeds (already "reached"), not search steps, so the search never walks through
     * open sky; cells outside every piece box are assumed terrain.
     */
    private static boolean isPassable(
            Long2ObjectOpenHashMap<BlockState> blocks,
            ColumnSurface surface,
            PieceMembership pieces,
            int x,
            int y,
            int z) {
        if (surface.height(x, z) <= y) {
            return false;
        }
        if (!pieces.contains(x, y, z)) {
            return false;
        }
        BlockState state = blocks.get(BlockPos.asLong(x, y, z));
        return state == null || !state.canOcclude();
    }

    /**
     * True when one of the four horizontal neighbours at the same Y is open sky. The neighbours may
     * lie just outside the searched volume: a passable cell at the volume edge can be seeded by the
     * open column next to it, and without that its neighbouring kept block would be dropped even
     * though it is exposed.
     */
    private static boolean hasOpenSkyNeighbour(ColumnSurface surface, int x, int y, int z) {
        for (int direction = 0; direction < 4; direction++) {
            if (surface.height(x + X_OFFSETS[direction], z + Z_OFFSETS[direction]) <= y) {
                return true;
            }
        }
        return false;
    }

    private static int clampSubtract(int value, int amount) {
        return value < Integer.MIN_VALUE + amount ? Integer.MIN_VALUE : value - amount;
    }

    private static int clampAdd(int value, int amount) {
        return value > Integer.MAX_VALUE - amount ? Integer.MAX_VALUE : value + amount;
    }
}
