package dimblend.experience.client.headlight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
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
 * where it hides the light. Client thread only; never allocates GL state.
 */
final class HeadlightLavaScan {
    /**
     * Voxel reads per recompute across all lamps, nearest section first. The shadow grid fill spends 32,768
     * reads per frame ({@code HeadlightWorldGrid.VOXEL_BUDGET}); palette-pruned sections cost nothing, so a
     * beam over ordinary terrain reads no voxels at all and only lava-bearing sections are walked.
     */
    static final int VOXEL_BUDGET = 65536;

    /** Below this the fluid state's height is not a surface worth drawing. */
    private static final float MIN_HEIGHT = 1.0E-3F;
    /** The cone's bounding box uses tan(half angle); anything past this would only overshoot the world. */
    private static final double MAX_HALF_ANGLE_DEGREES = 85.0;

    /** Lit top faces of every beam (uncapped, the caller applies {@link HeadlightLavaMath#MAX_SURFACES}) and the voxel reads spent. */
    record Result(List<LavaSurface> surfaces, int reads) {
    }

    static Result scan(ClientLevel level, List<HeadlightBeam> beams, double range,
                       double fullAngle, double strength, boolean occlusion) {
        if (beams.isEmpty()) {
            return new Result(List.of(), 0);
        }
        Map<Long, LavaSurface> lit = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double tanHalf = Math.tan(Math.toRadians(Math.min(fullAngle * 0.5, MAX_HALF_ANGLE_DEGREES)));
        int budget = VOXEL_BUDGET;
        for (HeadlightBeam beam : beams) {
            if (budget <= 0) {
                break;
            }
            budget = scanBeam(level, beam, range, fullAngle, strength, occlusion, tanHalf, lit, budget, cursor);
        }
        return new Result(new ArrayList<>(lit.values()), VOXEL_BUDGET - budget);
    }

    private static int scanBeam(ClientLevel level, HeadlightBeam beam, double range, double fullAngle,
                                double strength, boolean occlusion, double tanHalf,
                                Map<Long, LavaSurface> lit, int budget, BlockPos.MutableBlockPos cursor) {
        AABB cone = coneBounds(beam, range, tanHalf);
        int minX = Mth.floor(cone.minX);
        int maxX = Mth.floor(cone.maxX);
        int minZ = Mth.floor(cone.minZ);
        int maxZ = Mth.floor(cone.maxZ);
        int minY = Math.max(Mth.floor(cone.minY), level.getMinBuildHeight());
        int maxY = Math.min(Mth.floor(cone.maxY), level.getMaxBuildHeight() - 1);
        if (minX > maxX || minZ > maxZ || minY > maxY) {
            return budget;
        }
        for (SectionBox box : candidates(level, beam.lens(), range, minX, minY, minZ, maxX, maxY, maxZ)) {
            if (budget <= 0) {
                break;
            }
            budget -= scanBox(level, beam, box, range, fullAngle, strength, occlusion, lit, budget, cursor);
        }
        return budget;
    }

    /** Walks the box's voxels and adds exposed, lit lava top faces; returns the voxel reads spent. */
    private static int scanBox(ClientLevel level, HeadlightBeam beam, SectionBox box, double range,
                               double fullAngle, double strength, boolean occlusion,
                               Map<Long, LavaSurface> lit, int budget, BlockPos.MutableBlockPos cursor) {
        LevelChunkSection section = box.section();
        int reads = 0;
        for (int y = box.minY(); y <= box.maxY() && reads < budget; y++) {
            int localY = y & 15;
            for (int z = box.minZ(); z <= box.maxZ() && reads < budget; z++) {
                int localZ = z & 15;
                for (int x = box.minX(); x <= box.maxX() && reads < budget; x++) {
                    reads++;
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
                    cursor.set(x, y + 1, z);
                    BlockState above = level.getBlockState(cursor);
                    if (above.getFluidState().getType().isSame(fluid.getType())) {
                        continue;
                    }
                    if (above.isSolidRender(level, cursor)) {
                        continue;
                    }
                    double surfaceX = x + 0.5;
                    double surfaceY = y + height;
                    double surfaceZ = z + 0.5;
                    if (!HeadlightLavaMath.lit(beam.lens(), beam.direction(), surfaceX, surfaceY, surfaceZ,
                            range, fullAngle, strength)) {
                        continue;
                    }
                    if (occlusion && occluded(level, beam.lens(), surfaceX, surfaceY, surfaceZ, cursor)) {
                        continue;
                    }
                    lit.putIfAbsent(BlockPos.asLong(x, y, z), new LavaSurface(x, y, z, height));
                }
            }
        }
        return reads;
    }

    private static boolean hasLava(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA);
    }

    /**
     * Block-grid visibility from the lens to the surface point: the voxel DDA of the shadow trace
     * ({@code HeadlightOcclusionMath.trace}, lens cell skipped) against the cached occluder predicate, which
     * is the CPU twin of the grids the shader samples. Exact and window-free, unlike the camera-centred grid.
     */
    private static boolean occluded(ClientLevel level, Vec3 lens, double x, double y, double z,
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
                (gx, gy, gz) -> HeadlightOccluders.occludes(
                        level.getBlockState(cursor.set(originX + gx, originY + gy, originZ + gz))));
        return hit != HeadlightOcclusionMath.NO_HIT;
    }

    /**
     * Bounding box of the finite cone: the lens plus the end disc, whose extent along an axis is the cone
     * radius times {@code sqrt(1 - axis^2)}. Conservative for a cone, tight enough that a default 24-block
     * 50-degree beam spans ~12k blocks instead of the 110k of its range sphere.
     */
    private static AABB coneBounds(HeadlightBeam beam, double range, double tanHalf) {
        Vec3 lens = beam.lens();
        Vec3 direction = beam.direction();
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
    private static List<SectionBox> candidates(ClientLevel level, Vec3 lens, double range, int minX, int minY,
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
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
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
