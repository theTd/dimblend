package dimblend.radio.acoustics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Voxel surface class of a block state for reflection meshes: {@code 0} is open, otherwise the
 * {@link AcousticMaterials} index plus one. Tag and collision-shape queries run once per state, not per cell and
 * mesh build; {@link #clear()} drops the answers when tags reload or the level changes.
 */
public final class AcousticSurfaceKinds {
    private static final Map<BlockState, Byte> KINDS = new ConcurrentHashMap<>();

    /** @param blocks and @param pos are only consulted the first time {@code state} is seen */
    public static byte of(BlockState state, BlockGetter blocks, BlockPos pos) {
        if (state.isAir()) return 0;
        Byte cached = KINDS.get(state);
        if (cached != null) return cached;
        byte kind = classify(state, blocks, pos);
        KINDS.put(state, kind);
        return kind;
    }

    private static byte classify(BlockState state, BlockGetter blocks, BlockPos pos) {
        boolean open;
        try { open = state.getCollisionShape(blocks, pos, CollisionContext.empty()).isEmpty(); }
        catch (RuntimeException unsupportedShape) { open = false; }
        return open ? 0 : (byte) (AcousticBlockMaterials.of(state) + 1);
    }

    public static void clear() { KINDS.clear(); }

    private AcousticSurfaceKinds() { }
}
