package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import dimblend.band.KnownBandSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Bedrock-style protection for {@link RegionBoundaryWall} cubes.
 *
 * BOP's null block is a normal-hardness cube ({@code Properties.of()}), so survival
 * mining, explosions, and pistons would punch holes in the partition. Warp-gate cells
 * already use {@code strength(-1, 3600000)} and {@code PushReaction.BLOCK}.
 *
 * Covered paths:
 * <ul>
 *   <li>Hand breaking: {@link BlockEvent.BreakEvent} canceled for non-creative players.</li>
 *   <li>Explosions: positions filtered out of
 *       {@link ExplosionEvent.Detonate#getAffectedBlocks()}.</li>
 *   <li>Vanilla pistons: {@link PistonEvent.Pre} canceled when the structure would
 *       push or destroy a protected wall cell.</li>
 * </ul>
 * Digging feel (no crack progress) lives in {@link dimblend.mixin.BlockBehaviourMixin}
 * and uses the same {@link #isProtectedWall(BlockGetter, BlockPos, BlockState)} predicate.
 * Creative hand-breaking is allowed, matching vanilla bedrock.
 *
 * <p>BOP End Corruption trees also use {@code biomesoplenty:null_block} as trunks, and
 * TerraBlender injects that biome into the rotating dimension's End delegate. Protection
 * is therefore column-scoped: server uses {@link RegionBoundaryWall#ownsBoundaryColumn};
 * client (no ChunkGenerator) requires {@code x % bandSize == 0}. Same-lane open
 * boundaries have no worldgen wall; End-band trees are not on those surface/underground
 * seams.
 *
 * <p>Residual gap: {@code Level.destroyBlock} callers that post no event (wither,
 * /setblock, Create drills) can still remove wall cells. Same deliberate scope as
 * corridor tracks. Create mechanical pistons are in that bucket, not vanilla pistons.
 */
public final class RegionBoundaryWallProtector {

    private static volatile Block cachedWall;

    private RegionBoundaryWallProtector() {
    }

    public static boolean isWallBlock(BlockState state) {
        Block block = state.getBlock();
        Block cached = cachedWall;
        if (cached != null) {
            return block == cached;
        }
        Block resolved = BuiltInRegistries.BLOCK.get(RegionBoundaryWall.WALL_ID);
        if (resolved == Blocks.AIR) {
            return false;
        }
        cachedWall = resolved;
        return block == resolved;
    }

    public static boolean isProtectedWall(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof Level world)) {
            return false;
        }
        return isProtectedWall(world, pos, world.getBlockState(pos));
    }

    /**
     * Single predicate for mixin, break, explosion, and piston. Server levels consult
     * {@link RegionBoundaryWall#ownsBoundaryColumn}; other levels (the client chunk
     * cache has no generator) keep only the boundary-column X check.
     */
    public static boolean isProtectedWall(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(level instanceof Level world) || world.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return false;
        }
        if (!isWallBlock(state)) {
            return false;
        }
        return isOwnedBoundaryColumn(world, pos.getX());
    }

    private static boolean isOwnedBoundaryColumn(Level world, int blockX) {
        if (world instanceof ServerLevel server) {
            ChunkGenerator generator = server.getChunkSource().getGenerator();
            return generator instanceof RotatingChunkGenerator rotating
                    && RegionBoundaryWall.ownsBoundaryColumn(rotating, blockX);
        }
        return Math.floorMod(blockX, KnownBandSize.orDefault()) == 0;
    }

    public static void onBreakBlock(BlockEvent.BreakEvent event) {
        if (event.getPlayer().isCreative()) {
            return;
        }
        if (isProtectedWall(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof Level level)
                || level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> isProtectedWall(level, pos));
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof Level level)
                || level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        PistonStructureResolver structure = event.getStructureHelper();
        if (structure == null || !structure.resolve()) {
            return;
        }
        for (BlockPos pos : structure.getToPush()) {
            if (isProtectedWall(level, pos)) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : structure.getToDestroy()) {
            if (isProtectedWall(level, pos)) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
