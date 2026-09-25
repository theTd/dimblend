package dimblend.block;

import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gateway block entity for the warp gate. Only overrides the type and the face culling:
 * the vanilla {@code BlockEntityType.END_GATEWAY} rejects every block except
 * {@code Blocks.END_GATEWAY} in its ticking {@code isValidBlockState} check, which would
 * skip the ticker (freezing {@code age} at 0, keeping the renderer's bounding box
 * infinite) and spam the log. All behavior stays vanilla gateway bookkeeping; the
 * teleport entry point is cut in {@link WarpGateBlock#entityInside}.
 */
public final class WarpGateBlockEntity extends TheEndGatewayBlockEntity {
    public WarpGateBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public BlockEntityType<?> getType() {
        return DimBlendRegistries.WARP_GATE_BE.get();
    }

    /**
     * The inherited vanilla culling ({@code Block.shouldRenderFace}) drops every face
     * whose neighbor fully occludes it — including faces flush against the solid null
     * block wall and carved terrain, which left gaps in the starfield along the
     * cross-section's edges. Cull only faces shared with another gate cell instead, so
     * the starfield covers the whole exposed cross-section. Bonus: this is cheaper than
     * the per-frame occlusion-shape lookup it replaces, and particles (which reuse this
     * check) now only spawn on exposed faces.
     */
    @Override
    public boolean shouldRenderFace(Direction face) {
        if (this.level == null) {
            return face.getAxis() == Direction.Axis.Y;
        }
        return !this.level.getBlockState(this.getBlockPos().relative(face)).is(this.getBlockState().getBlock());
    }
}
