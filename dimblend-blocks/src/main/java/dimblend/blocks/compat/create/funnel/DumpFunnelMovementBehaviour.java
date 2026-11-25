package dimblend.blocks.compat.create.funnel;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 装置（Contraption / 列车）里的卸料漏斗。
 *
 * <p>Create 自带的 {@code FunnelMovementBehaviour} 是"按过滤槽抽 N 个"的语义，还不认
 * "容器内该物品总数 &gt; 阈值"这条规则；它在装置里直接读 {@code FilterAmount} 与
 * {@code UpTo}，会绕过 {@link DumpFilteringBehaviour}。这里改成同一套卸料判定
 * （见 {@link DumpFunnels#extractFromContraption}）：过滤槽为空 → 不抽；
 * 否则只对"总数严格大于阈值"的物品各抽 1 个。</p>
 */
public class DumpFunnelMovementBehaviour implements MovementBehaviour {

    @Override
    public Vec3 getActiveAreaOffset(MovementContext context) {
        Direction facing = DumpFunnels.funnelFacing(context.state);
        Vec3 vec = Vec3.atLowerCornerOf(facing.getNormal());
        return vec.scale(facing == Direction.UP ? 0.65D : 0.15D);
    }

    @Override
    public void visitNewPosition(MovementContext context, BlockPos pos) {
        MovementBehaviour.super.visitNewPosition(context, pos);
        Level world = context.world;
        if (world == null) {
            return;
        }
        Vec3 entityPos = context.position;
        if (DumpFunnels.funnelFacing(context.state) != Direction.DOWN) {
            entityPos = entityPos.add(0.0D, -0.5D, 0.0D);
        }
        if (!world.getBlockState(pos).getCollisionShape((BlockGetter) world, pos).isEmpty()) {
            return;
        }
        if (!world.getEntitiesOfClass(ItemEntity.class, new AABB(BlockPos.containing((Position) entityPos)))
                .isEmpty()) {
            return;
        }
        ItemStack extract = DumpFunnels.extractFromContraption(context, DumpFunnelRules.EXTRACT_AMOUNT);
        if (extract.isEmpty() || world.isClientSide) {
            return;
        }
        ItemEntity entity = new ItemEntity(world, entityPos.x, entityPos.y, entityPos.z, extract);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setPickUpDelay(5);
        world.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.0625F, 0.1F);
        world.addFreshEntity((Entity) entity);
    }
}
