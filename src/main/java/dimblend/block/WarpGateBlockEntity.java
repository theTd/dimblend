package dimblend.block;

import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gateway block entity for the warp gate. Only overrides the type: the vanilla
 * {@code BlockEntityType.END_GATEWAY} rejects every block except {@code Blocks.END_GATEWAY}
 * in its ticking {@code isValidBlockState} check, which would skip the ticker (freezing
 * {@code age} at 0, keeping the renderer's bounding box infinite) and spam the log. All
 * behavior stays vanilla gateway bookkeeping; the teleport entry point is cut in
 * {@link WarpGateBlock#entityInside}.
 */
public final class WarpGateBlockEntity extends TheEndGatewayBlockEntity {
    public WarpGateBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public BlockEntityType<?> getType() {
        return DimBlendRegistries.WARP_GATE_BE.get();
    }
}
