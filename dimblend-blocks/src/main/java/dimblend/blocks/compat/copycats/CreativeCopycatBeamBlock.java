package dimblend.blocks.compat.copycats;

import com.copycatsplus.copycats.content.copycat.beam.CopycatBeamBlock;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import com.copycatsplus.copycats.utility.InteractionUtils;
import com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.placement.PoleHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.function.Predicate;

import static net.minecraft.core.Direction.Axis;

/**
 * C2 创造模式伪装粱：行为与 Copycats+ 伪装粱一致，差异同
 * {@link CreativeCopycatSlabBlock}（自有 BE、扳手只撕材质不拆本体、蓝图免取材）。
 */
public class CreativeCopycatBeamBlock extends CopycatBeamBlock implements SpecialBlockItemRequirement {

    /**
     * 创造粱极柱连放：原版 helper 的连杆状态谓词只认原版粱
     * （{@code CCBlocks.COPYCAT_BEAM::has}），连放链路过创造粱即中断；
     * 此 helper 原版/创造粱一并接受，物品侧原版已是 instanceof 全覆盖。
     */
    private static final int CREATIVE_PLACEMENT_HELPER_ID =
            PlacementHelpers.register(new CreativeBeamPlacementHelper());

    public CreativeCopycatBeamBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends CCCopycatBlockEntity> getBlockEntityType() {
        return CreativeCopycats.CREATIVE_COPYCAT.get();
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        // 只撕材质：不调 super（潜行扳手的原版语义是拆掉本体）
        return onWrenched(state, context);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 方块级保险：覆盖常规 BlockItem 放置。注意 helper 连放路径
        // （PlacementOffset.placeInWorld）不走这里，由 useItemOn 内创造门把守。
        Player player = context.getPlayer();
        if (player == null || !player.isCreative()) {
            return null;
        }
        return super.getStateForPlacement(context);
    }

    @Override
    public ItemRequirement getRequiredItems(BlockState state, BlockEntity blockEntity) {
        return ItemRequirement.NONE;
    }

    @Override
    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        return InteractionUtils.sequentialItem(
                // 生存旁路封堵：helper 连放（placeInWorld）不走 canPlace/getStateForPlacement，
                // 非创造一律放行到扳手/材质逻辑。
                () -> player != null && player.isCreative()
                        ? InteractionUtils.usePlacementHelper(CREATIVE_PLACEMENT_HELPER_ID, stack, state, level,
                                pos, player, hand, hitResult)
                        : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,
                () -> super.useItemOn(stack, state, level, pos, player, hand, hitResult));
    }

    @ParametersAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    private static class CreativeBeamPlacementHelper extends PoleHelper<Axis> {

        private CreativeBeamPlacementHelper() {
            super(state -> state.getBlock() instanceof CopycatBeamBlock, state -> state.getValue(AXIS), AXIS);
        }

        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof CopycatBeamBlock;
        }
    }
}
