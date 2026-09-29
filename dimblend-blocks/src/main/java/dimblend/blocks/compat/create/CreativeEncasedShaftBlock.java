package dimblend.blocks.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedShaftBlock;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.function.Supplier;

/**
 * 创造模式套壳传动杆（安山岩/黄铜）：行为与 Create 套壳轴一致，差异：
 * <ul>
 * <li>BE 用本模组自己的 {@code creative_encased_shaft} 类型（同板块裸块理由）</li>
 * <li>普通扳手右键按模式分派（2026-09-29 拍板）：<b>创造</b>恢复上游旋转语义；
 * <b>生存</b>无效（只能潜行扳手拆壳）</li>
 * <li>潜行扳手拆壳变回 {@link CreativeShaftBlock}——上游实现把方块硬编码换回
 * {@code AllBlocks.SHAFT}，必须覆写指向创造轴；壳不返还（上游套壳本就不耗壳，对称）。
 * 实现照抄上游（破坏粒子 + switchToBlockState）只换目标方块</li>
 * <li>蓝图取材 NONE（上游实现了 {@code SpecialBlockItemRequirement} 指向原版轴，
 * 覆写之——原理图加农炮不应索要无法获取的本方块）</li>
 * <li>无物品形态（与 Create 一致）；中键选取修正为本板块裸轴/壳，不再泄漏原版轴</li>
 * </ul>
 */
public class CreativeEncasedShaftBlock extends EncasedShaftBlock {

    private final Supplier<? extends BlockEntityType<? extends KineticBlockEntity>> blockEntityType;

    public CreativeEncasedShaftBlock(Properties properties, Supplier<Block> casing,
            Supplier<? extends BlockEntityType<? extends KineticBlockEntity>> blockEntityType) {
        super(properties, casing);
        this.blockEntityType = blockEntityType;
    }

    @Override
    public BlockEntityType<? extends KineticBlockEntity> getBlockEntityType() {
        return blockEntityType.get();
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        // 创造：恢复上游旋转语义（2026-09-29 拍板，同裸件）；
        // 生存：encased 上普通扳手仍禁（只能潜行扳手拆壳）
        if (player != null && player.isCreative()) {
            return super.onWrenched(state, context);
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        // 拆壳：照抄上游粒子 + switchToBlockState 实现，目标换成本板块裸轴
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        context.getLevel()
                .levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, context.getClickedPos(), Block.getId(state));
        KineticBlockEntity.switchToBlockState(context.getLevel(), context.getClickedPos(),
                CreativeKinetics.CREATIVE_SHAFT.get().defaultBlockState()
                        .setValue(AXIS, state.getValue(AXIS)));
        return InteractionResult.SUCCESS;
    }

    @Override
    public ItemRequirement getRequiredItems(BlockState state, BlockEntity blockEntity) {
        return ItemRequirement.NONE;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
            Player player) {
        // 上游按命中面给原版轴/壳；本板块给创造轴，壳仍是 Create 壳物品
        if (target instanceof BlockHitResult blockHit) {
            return blockHit.getDirection().getAxis() == getRotationAxis(state)
                    ? CreativeKinetics.CREATIVE_SHAFT_ITEM.get().getDefaultInstance()
                    : getCasing().asItem().getDefaultInstance();
        }
        return super.getCloneItemStack(state, target, level, pos, player);
    }
}
