package dimblend.experience.headlight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Collects the lava top faces the beams light: for each lamp the cone's bounding box, palette-pruned chunk
 * sections nearest the lens first, the exposed-top rule, the same cone/attenuation test as the surface
 * shader ({@link HeadlightLavaMath#lit}) and, with shadows on, a block raycast through the same occluder
 * predicate as the shadow grids ({@link HeadlightOccluders}), so a wall in the way hides the outline exactly
 * where it hides the light.
 *
 * <p>Takes a plain {@link Level}, so the client highlight and the server's marked-lava query walk the same code:
 * the client outlines every lit face of the beams it draws, and the observer blaze's tracker scans once per
 * recompute and answers each observer with {@link HeadlightLavaMath#anyWithin} against that one result. Every
 * block read that is not taken from an already-listed chunk section goes through {@link LoadedChunks}, so the
 * server-side scan never loads a chunk; a cell in an unloaded chunk reads as the client's void air.</p>
 */
public final class HeadlightLavaScan {
    /**
     * Voxel reads per recompute across all lamps, nearest section first. The shadow grid fill spends 32,768
     * reads per frame ({@code HeadlightWorldGrid.VOXEL_BUDGET}); palette-pruned sections cost nothing, so a
     * beam over ordinary terrain reads no voxels at all and only lava-bearing sections are walked.
     */
    public static final int VOXEL_BUDGET = 65536;

    /** Below this the fluid state's height is not a surface worth drawing. */
    private static final float MIN_HEIGHT = 1.0E-3F;
    /** The cone's bounding box uses tan(half angle); anything past this would only overshoot the world. */
    private static final double MAX_HALF_ANGLE_DEGREES = 85.0;

    /**
     * Lit top faces of every beam (deduplicated, uncapped: the caller applies {@link HeadlightLavaMath#MAX_SURFACES}
     * for the outline and the same count for the server's marked set) and the voxel reads spent.
     */
    public record Result(List<LavaSurface> surfaces, int reads) {
    }

    /** Called for every lit top face. */
    @FunctionalInterface
    private interface SurfaceVisitor {
        void visit(int x, int y, int z, float height);
    }

    /**
     * Every lit top face of {@code rays}, deduplicated by block position, and the voxel reads spent. The client
     * draws (a capped, edged version of) this set; the server keeps it as the marked set of its recompute window,
     * so one scan answers every observer.
     */
    public static Result scan(Level level, List<? extends HeadlightRay> rays, double range,
                              double fullAngle, double strength, boolean occlusion) {
        Map<Long, LavaSurface> lit = new HashMap<>();
        Walk walk = new Walk();
        walkRays(level, rays, range, fullAngle, strength, occlusion,
                (x, y, z, height) -> lit.putIfAbsent(BlockPos.asLong(x, y, z), new LavaSurface(x, y, z, height)), walk);
        return new Result(new ArrayList<>(lit.values()), VOXEL_BUDGET - walk.budget);
    }

    /** Budget and traversal state for one scan. */
    private static final class Walk {
        private int budget = VOXEL_BUDGET;
    }

    private static void walkRays(Level level, List<? extends HeadlightRay> rays, double range, double fullAngle,
                                 double strength, boolean occlusion, SurfaceVisitor visitor, Walk walk) {
        if (rays.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double tanHalf = Math.tan(Math.toRadians(Math.min(fullAngle * 0.5, MAX_HALF_ANGLE_DEGREES)));
        for (HeadlightRay ray : rays) {
            if (walk.budget <= 0) {
                break;
            }
            walkRay(level, ray, range, fullAngle, strength, occlusion, tanHalf, visitor, walk, cursor);
        }
    }

    private static void walkRay(Level level, HeadlightRay ray, double range, double fullAngle,
                                double strength, boolean occlusion, double tanHalf, SurfaceVisitor visitor,
                                Walk walk, BlockPos.MutableBlockPos cursor) {
        AABB cone = coneBounds(ray, range, tanHalf);
        int minX = Mth.floor(cone.minX);
        int maxX = Mth.floor(cone.maxX);
        int minZ = Mth.floor(cone.minZ);
        int maxZ = Mth.floor(cone.maxZ);
        int minY = Math.max(Mth.floor(cone.minY), level.getMinBuildHeight());
        int maxY = Math.min(Mth.floor(cone.maxY), level.getMaxBuildHeight() - 1);
        if (minX > maxX || minZ > maxZ || minY > maxY) {
            return;
        }
        for (SectionBox box : candidates(level, ray.lens(), range, minX, minY, minZ, maxX, maxY, maxZ)) {
            if (walk.budget <= 0) {
                break;
            }
            walkBox(level, ray, box, range, fullAngle, strength, occlusion, visitor, walk, cursor);
        }
    }

    /** Walks the box's voxels and reports exposed, lit lava top faces; spends voxel reads from the budget. */
    private static void walkBox(Level level, HeadlightRay ray, SectionBox box, double range,
                                 double fullAngle, double strength, boolean occlusion,
                                 SurfaceVisitor visitor, Walk walk, BlockPos.MutableBlockPos cursor) {
        LevelChunkSection section = box.section();
        for (int y = box.minY(); y <= box.maxY() && walk.budget > 0; y++) {
            int localY = y & 15;
            for (int z = box.minZ(); z <= box.maxZ() && walk.budget > 0; z++) {
                int localZ = z & 15;
                for (int x = box.minX(); x <= box.maxX() && walk.budget > 0; x++) {
                    walk.budget--;
                    BlockState state = section.getBlockState(x & 15, localY, localZ);
                    FluidState fluid = state.getFluidState();
                    if (!fluid.is(FluidTags.LAVA)) {
                        continue;
                    }
                    // Exposed top only: the same fluid above means the surface is higher up, and a full solid
                    // block above (BlockBehaviour.isSolidRender, BlockBehaviour.java:603-610) caps the pool.
                    float height = fluid.getOwnHeight();
                    if (height < MIN_HEIGHT) {
                        continue;
                    }
                    BlockState above = LoadedChunks.state(level, cursor.set(x, y + 1, z));
                    if (above == null) {
                        // Unloaded above the pool: the exposure cannot be established without loading a chunk.
                        continue;
                    }
                    if (above.getFluidState().getType().isSame(fluid.getType())) {
                        continue;
                    }
                    if (!above.isAir() && !LoadedChunks.shapeNeighbourhoodLoaded(level, x, y + 1, z)) {
                        // A fence, wall or pane above would read its neighbours; near the edge of the loaded region
                        // that could pull a chunk in, so the exposure is treated as unknown.
                        continue;
                    }
                    if (above.isSolidRender(level, cursor)) {
                        continue;
                    }
                    double surfaceX = x + 0.5;
                    double surfaceY = y + height;
                    double surfaceZ = z + 0.5;
                    if (!HeadlightLavaMath.lit(ray.lens(), ray.direction(), surfaceX, surfaceY, surfaceZ,
                            range, fullAngle, strength)) {
                        continue;
                    }
                    if (occlusion && occluded(level, ray.lens(), surfaceX, surfaceY, surfaceZ, cursor)) {
                        continue;
                    }
                    visitor.visit(x, y, z, height);
                }
            }
        }
    }

    private static boolean hasLava(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA);
    }

    /**
     * Block-grid visibility from the lens to the surface point: the voxel DDA of the shadow trace
     * ({@code HeadlightOcclusionMath.trace}, lens cell skipped) against the cached occluder predicate, which
     * is the CPU twin of the grids the shader samples. Exact and window-free, unlike the camera-centred grid.
     *
     * <p>Only loaded cells are read ({@link LoadedChunks}); an unloaded cell is unknown, so the trace treats it as
     * non-occluding -- the same answer the client gets for a cell it does not have. Server note: only world blocks
     * are tested here. Vehicle (plot) blocks are not occluders for the server-side query -- the divergence is
     * accepted and documented in the requirements spec.</p>
     */
    private static boolean occluded(Level level, Vec3 lens, double x, double y, double z,
                                    BlockPos.MutableBlockPos cursor) {
        int originX = Mth.floor(Math.min(lens.x, x)) - 1;
        int originY = Mth.floor(Math.min(lens.y, y)) - 1;
        int originZ = Mth.floor(Math.min(lens.z, z)) - 1;
        int sizeX = Mth.floor(Math.max(lens.x, x)) + 2 - originX;
        int sizeY = Mth.floor(Math.max(lens.y, y)) + 2 - originY;
        int sizeZ = Mth.floor(Math.max(lens.z, z)) + 2 - originZ;
        double hit = HeadlightOcclusionMath.trace(
                lens.x - originX, lens.y - originY, lens.z - originZ,
                x - originX, y - originY, z - originZ,
                sizeX, sizeY, sizeZ,
                (gx, gy, gz) -> {
                    BlockState state = LoadedChunks.state(level, cursor.set(originX + gx, originY + gy, originZ + gz));
                    return state != null && HeadlightOccluders.occludes(state);
                });
        return hit != HeadlightOcclusionMath.NO_HIT;
    }

    /**
     * Bounding box of the finite cone: the lens plus the end disc, whose extent along an axis is the cone
     * radius times {@code sqrt(1 - axis^2)}. Conservative for a cone, tight enough that a default 24-block
     * 50-degree beam spans ~12k blocks instead of the 110k of its range sphere.
     */
    private static AABB coneBounds(HeadlightRay ray, double range, double tanHalf) {
        Vec3 lens = ray.lens();
        Vec3 direction = ray.direction();
        Vec3 tip = lens.add(direction.scale(range));
        double radius = range * tanHalf;
        double extentX = radius * Math.sqrt(Math.max(0.0, 1.0 - direction.x * direction.x));
        double extentY = radius * Math.sqrt(Math.max(0.0, 1.0 - direction.y * direction.y));
        double extentZ = radius * Math.sqrt(Math.max(0.0, 1.0 - direction.z * direction.z));
        return new AABB(
                Math.min(lens.x, tip.x - extentX), Math.min(lens.y, tip.y - extentY), Math.min(lens.z, tip.z - extentZ),
                Math.max(lens.x, tip.x + extentX), Math.max(lens.y, tip.y + extentY), Math.max(lens.z, tip.z + extentZ));
    }

    /**
     * Loaded sections overlapping the search box that can hold lit lava, nearest the lens first: a section
     * whose nearest point is past the beam's reach cannot hold a lit point (the nearest point bounds every
     * point in it), and a palette without lava never pays for a voxel walk.
     */
    private static List<SectionBox> candidates(Level level, Vec3 lens, double range, int minX, int minY,
                                               int minZ, int maxX, int maxY, int maxZ) {
        List<SectionBox> candidates = new ArrayList<>();
        int minChunkX = SectionPos.blockToSectionCoord(minX);
        int maxChunkX = SectionPos.blockToSectionCoord(maxX);
        int minChunkZ = SectionPos.blockToSectionCoord(minZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
        int minSectionY = SectionPos.blockToSectionCoord(minY);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // Non-loading lookup: the scan never forces chunk generation, it just skips what is missing.
                if (!(level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                    continue;
                }
                int baseX = SectionPos.sectionToBlockCoord(chunkX);
                int baseZ = SectionPos.sectionToBlockCoord(chunkZ);
                int x0 = Math.max(minX, baseX);
                int x1 = Math.min(maxX, baseX + 15);
                int z0 = Math.max(minZ, baseZ);
                int z1 = Math.min(maxZ, baseZ + 15);
                if (x0 > x1 || z0 > z1) {
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    if (sectionY < chunk.getMinSection() || sectionY >= chunk.getMaxSection()) {
                        continue;
                    }
                    int baseY = SectionPos.sectionToBlockCoord(sectionY);
                    int y0 = Math.max(minY, baseY);
                    int y1 = Math.min(maxY, baseY + 15);
                    if (y0 > y1) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(HeadlightLavaScan::hasLava)) {
                        continue;
                    }
                    double distance = nearestDistance(lens, x0, y0, z0, x1, y1, z1);
                    if (distance > range) {
                        continue;
                    }
                    candidates.add(new SectionBox(section, x0, y0, z0, x1, y1, z1, distance * distance));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(SectionBox::distanceSquared));
        return candidates;
    }

    /** Distance from a point to the inclusive block box [x0..x1] x [y0..y1] x [z0..z1]. */
    private static double nearestDistance(Vec3 point, int x0, int y0, int z0, int x1, int y1, int z1) {
        double dx = Math.max(Math.max(x0 - point.x, point.x - (x1 + 1)), 0.0);
        double dy = Math.max(Math.max(y0 - point.y, point.y - (y1 + 1)), 0.0);
        double dz = Math.max(Math.max(z0 - point.z, point.z - (z1 + 1)), 0.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A section and the world-coordinate box of it that the search needs (inclusive bounds). */
    private record SectionBox(LevelChunkSection section, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                              double distanceSquared) {
    }

    private HeadlightLavaScan() {
    }
}
