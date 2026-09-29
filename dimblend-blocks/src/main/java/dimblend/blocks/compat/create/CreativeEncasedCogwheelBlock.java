package dimblend.blocks.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogwheelBlock;
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
 * 创造模式套壳齿轮（安山岩/黄铜，仅小号）：行为与 Create 套壳齿轮一致，差异：
 * <ul>
 * <li>BE 用本模组自己的 {@code creative_encased_cogwheel} 类型（同板块裸块理由）</li>
 * <li>普通扳手右键按模式分派（2026-09-29 拍板）：<b>创造</b>恢复上游语义
 * （点击轴端面翻转 TOP/BOTTOM_SHAFT，其余面旋转）；<b>生存</b>无效
 * （只能潜行扳手拆壳）</li>
 * <li>潜行扳手拆壳变回 {@link CreativeCogwheelBlock}（上游硬编码换回
 * {@code AllBlocks.COGWHEEL}，覆写指向创造齿轮；壳不返还，与套壳不耗壳对称）；
 * TOP/BOTTOM_SHAFT 轴头随方块一同消失（上游同款语义）</li>
 * <li>蓝图取材 NONE、无物品形态、中键选取修正，理由同
 * {@link CreativeEncasedShaftBlock}</li>
 * </ul>
 *
 * <p>套壳入口（handleEncasing）不覆写：上游实现基于 {@code defaultBlockState()}
 * 推导轴与轴头，子类天然指向本方块。</p>
 */
public class CreativeEncasedCogwheelBlock extends EncasedCogwheelBlock {

    private final Supplier<? extends BlockEntityType<? extends SimpleKineticBlockEntity>> blockEntityType;

    public CreativeEncasedCogwheelBlock(Properties properties, Supplier<Block> casing,
            Supplier<? extends BlockEntityType<? extends SimpleKineticBlockEntity>> blockEntityType) {
        super(properties, false, casing);
        this.blockEntityType = blockEntityType;
    }

    @Override
    public BlockEntityType<? extends SimpleKineticBlockEntity> getBlockEntityType() {
        return blockEntityType.get();
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        // 创造：恢复上游语义（点击轴端面翻转 TOP/BOTTOM_SHAFT，其余面旋转）；
        // 生存：encased 上普通扳手仍禁（只能潜行扳手拆壳）
        if (player != null && player.isCreative()) {
            return super.onWrenched(state, context);
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        // 拆壳：照抄上游粒子 + switchToBlockState 实现，目标换成本板块裸齿轮
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        context.getLevel()
                .levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, context.getClickedPos(), Block.getId(state));
        KineticBlockEntity.switchToBlockState(context.getLevel(), context.getClickedPos(),
                CreativeKinetics.CREATIVE_COGWHEEL.get().defaultBlockState()
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
        // 上游按命中面给原版齿轮/壳；本板块给创造齿轮，壳仍是 Create 壳物品
        if (target instanceof BlockHitResult blockHit) {
            return blockHit.getDirection().getAxis() != getRotationAxis(state)
                    ? CreativeKinetics.CREATIVE_COGWHEEL_ITEM.get().getDefaultInstance()
                    : getCasing().asItem().getDefaultInstance();
        }
        return super.getCloneItemStack(state, target, level, pos, player);
    }
}
