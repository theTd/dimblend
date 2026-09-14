package dimblend.block;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.companion.SableCompanion;
import dimblend.DimBlendRegistries;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Warp gate: fills the railway corridor cross-section at region boundaries.
 *
 * Extends the vanilla end gateway, so it inherits the starfield block-entity look, the
 * portal particles, the light, and the unbreakable/no-drop properties — but it is a
 * barrier, not a portal: {@link #entityInside} is overridden to a no-op so the teleport
 * chain never starts.
 *
 * Collision is split by the entity on {@link CollisionContext}, the same pattern as
 * scaffolding. Vanilla {@code CollisionContext.empty()} <em>is</em> an
 * {@link EntityCollisionContext} with a null entity (not a separate type), and
 * {@code BlockState.getCollisionShape(level, pos)} caches that empty-context result
 * for the 2-arg path Sable and Create trains use:
 * <ul>
 *   <li>null entity / empty context — empty. Sable {@code isSolid} and Create
 *       {@code ContraptionCollider} both see air;</li>
 *   <li>entity and {@link #mayPass} — empty, so a player on a Sable structure is
 *       not scraped off;</li>
 *   <li>entity and not {@link #mayPass} — full cube, walking players hit a wall
 *       on both client and server.</li>
 * </ul>
 * {@link dimblend.worldgen.WarpGatePassageGuard} stays as a clip/teleport safety net
 * and shares {@link #mayPass}.
 */
public final class WarpGateBlock extends EndGatewayBlock {
    public WarpGateBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * Vanilla teleports here. The warp gate must not: standing inside it is decided by
     * collision + {@link dimblend.worldgen.WarpGatePassageGuard}, not by portal logic.
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Entity entity = context instanceof EntityCollisionContext entityContext
                ? entityContext.getEntity()
                : null;
        return collisionShapeFor(entity);
    }

    /**
     * {@code entity == null} covers {@link CollisionContext#empty()} and the 2-arg
     * {@code BlockState} collision cache. That path must stay empty so Sable's world
     * mesh does not bake the gate as solid.
     */
    static VoxelShape collisionShapeFor(@Nullable Entity entity) {
        if (entity == null) {
            return Shapes.empty();
        }
        return mayPass(entity) ? Shapes.empty() : Shapes.block();
    }

    /**
     * Who may occupy a gate cell. Used by {@link #getCollisionShape} and
     * {@link dimblend.worldgen.WarpGatePassageGuard}.
     *
     * <ul>
     *   <li>Create contraptions (including carriages) — they are Sable kinematic bodies;</li>
     *   <li>players in creative or spectator;</li>
     *   <li>other players only while standing on / riding a Sable structure
     *       ({@link SableCompanion} is a no-op when Sable is absent).</li>
     * </ul>
     */
    public static boolean mayPass(Entity entity) {
        if (entity instanceof AbstractContraptionEntity) {
            return true;
        }
        if (entity instanceof Player player) {
            if (player.isSpectator() || player.isCreative()) {
                return true;
            }
            return SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player) != null;
        }
        return false;
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

    /**
     * End-gateway look without {@code noCollission()}: collision is decided per context
     * in {@link #getCollisionShape}. Suffocation and view-blocking stay off so clipping
     * into a cell does not black out the view or deal wall damage, and occlusion stays
     * off so the starfield faces are not culled as a solid cube.
     */
    public static BlockBehaviour.Properties createProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .noOcclusion()
                .isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false)
                .isValidSpawn((state, level, pos, type) -> false)
                .lightLevel(state -> 15)
                .strength(-1.0F, 3600000.0F)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK);
    }
}
