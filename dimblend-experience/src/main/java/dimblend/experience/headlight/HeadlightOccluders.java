package dimblend.experience.headlight;

import dimblend.experience.compat.dndecor.FrontlightSpotlight;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Cached per-BlockState occluder classification and section-to-voxel reads. Shared by the client's shadow
 * grids and by the server's marked-lava query, so the cache is keyed by state instance (identity hash) in a
 * concurrent map: the client render thread and the server tick both classify states.
 */
public final class HeadlightOccluders {
    public static final byte CLEAR = 0;
    public static final byte SOLID = (byte) 255;
    private static final ConcurrentHashMap<BlockState, Boolean> CACHE = new ConcurrentHashMap<>();

    public static boolean occludes(BlockState state) {
        Boolean cached = CACHE.get(state);
        if (cached == null) {
            cached = classify(state);
            CACHE.put(state, cached);
        }
        return cached;
    }

    private static boolean classify(BlockState state) {
        if (state.isAir() || !state.canOcclude()) return false;
        double coverage;
        try {
            var shape = state.getOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            coverage = 0.0;
            for (AABB box : shape.toAabbs()) coverage += box.getXsize() * box.getYsize() * box.getZsize();
        } catch (RuntimeException ignored) {
            // A block whose shape needs real level context: treat as a full cube if it claims to occlude.
            coverage = 1.0;
        }
        return HeadlightOcclusionMath.occludes(FrontlightSpotlight.isFrontlight(state), true, Math.min(coverage, 1.0));
    }

    /** Whether a section can contain any occluder (palette check, no voxel reads). */
    public static boolean mayOcclude(LevelChunkSection section) {
        return section != null && !section.hasOnlyAir() && section.maybeHas(HeadlightOccluders::occludes);
    }

    /**
     * Writes the occupancy of section voxels [x0,x1) x [y0,y1) x [z0,z1) (section-local) into {@code out} at
     * {@code base + x + y * strideY + z * strideZ} (relative to the region's minimum). Returns whether any voxel
     * occludes.
     */
    public static boolean read(LevelChunkSection section, int x0, int y0, int z0, int x1, int y1, int z1,
                        ByteBuffer out, int base, int strideY, int strideZ) {
        boolean any = false;
        BlockState last = null;
        byte value = CLEAR;
        for (int z = z0; z < z1; z++) {
            for (int y = y0; y < y1; y++) {
                int row = base + (y - y0) * strideY + (z - z0) * strideZ;
                for (int x = x0; x < x1; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state != last) {
                        last = state;
                        value = occludes(state) ? SOLID : CLEAR;
                    }
                    out.put(row + x - x0, value);
                    any |= value != CLEAR;
                }
            }
        }
        return any;
    }

    public static void clear() { CACHE.clear(); }

    private HeadlightOccluders() {}
}
