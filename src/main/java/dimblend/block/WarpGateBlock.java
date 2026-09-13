package dimblend.block;

import dimblend.DimBlendRegistries;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Warp gate: fills the railway corridor cross-section at region boundaries.
 *
 * Extends the vanilla end gateway, so it inherits the starfield block-entity look, the
 * portal particles, the light, the missing collision, and the unbreakable/no-drop
 * properties — but it is a barrier, not a portal: {@link #entityInside} is overridden to
 * a no-op so entities are never marked inside a portal and the teleport chain
 * (entityInside -&gt; setAsInsidePortal -&gt; getPortalDestination) never starts. Passage
 * control lives in {@link dimblend.worldgen.WarpGatePassageGuard}, which reverts
 * disallowed entities every tick; keeping collision empty makes the per-entity decision
 * (players on a Sable structure pass, everything else is blocked) possible without
 * mixin-ing the collision pipeline.
 */
public final class WarpGateBlock extends EndGatewayBlock {
    public WarpGateBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * Vanilla teleports here. The warp gate must not: standing inside it is decided by
     * {@link dimblend.worldgen.WarpGatePassageGuard}, not by portal logic.
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarpGateBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return createTickerHelper(
                blockEntityType,
                DimBlendRegistries.WARP_GATE_BE.get(),
                level.isClientSide ? TheEndGatewayBlockEntity::beamAnimationTick : TheEndGatewayBlockEntity::portalTick
        );
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        return false;
    }

    /** Mirrors the vanilla {@code Blocks.END_GATEWAY} properties exactly. */
    public static BlockBehaviour.Properties createProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .noCollission()
                .lightLevel(state -> 15)
                .strength(-1.0F, 3600000.0F)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK);
    }
}
