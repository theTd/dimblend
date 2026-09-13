package dimblend.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Warp gate: fills the railway corridor cross-section at region boundaries. The block is
 * collision-free on purpose — passage control lives in
 * {@link dimblend.worldgen.WarpGatePassageGuard}, which reverts disallowed entities every
 * tick. Keeping collision empty makes the per-entity decision (players on a Sable
 * structure pass, everything else is blocked) possible without mixin-ing the collision
 * pipeline.
 */
public final class WarpGateBlock extends Block {
    public WarpGateBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        return false;
    }

    public static Properties createProperties() {
        return Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(-1.0F, 3600000.0F)
                .noCollission()
                .noOcclusion()
                .noLootTable()
                .pushReaction(PushReaction.BLOCK);
    }
}
