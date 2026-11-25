package dimblend.experience.compat.connected.mobdisplay;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 生物展示催化剂（Audit Default #12）：{@code create_connected:empty_fan_catalyst} 的"装进生物"版本。
 *
 * <p>外观完全复刻风扇催化剂的中空黄铜框（内圈 12×12 换成玻璃），方块实体里存着那只
 * {@code creatorsword:capture_box}，客户端把盒子里记录的生物渲染在方块中心（见
 * {@link MobDisplayCatalystRenderer}）。</p>
 *
 * <p>取回方式三种，都要把抓捕盒原样还给玩家：</p>
 * <ol>
 * <li>右键（非潜行）：由 {@link MobDisplayCatalystInteraction} 就地换回空催化剂并给盒；</li>
 * <li>潜行 + 扳手：{@link #onSneakWrenched} 掉空催化剂 + 盒，方块消失；</li>
 * <li>镐子挖：{@link #onRemove} 掉盒，方块本体照 loot 表掉空催化剂。</li>
 * </ol>
 */
public final class MobDisplayCatalystBlock extends Block implements EntityBlock, IWrenchable {

    public MobDisplayCatalystBlock(Properties properties) {
        super(properties);
    }

    // --- 方块实体 ---

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MobDisplayCatalystBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (!level.isClientSide) {
            // 服务端没有逐 tick 的事：盒子变化时由 interaction 主动广播
            return null;
        }
        return (tickingLevel, pos, tickingState, blockEntity) -> {
            if (blockEntity instanceof MobDisplayCatalystBlockEntity catalyst) {
                catalyst.clientTick();
            }
        };
    }

    // --- 身份：中键/投影看到的是空催化剂（本方块没有自己的物品形态） ---

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        Item empty = item(MobDisplayCatalystBlocks.EMPTY_FAN_CATALYST_ID);
        return empty == null ? ItemStack.EMPTY : new ItemStack(empty);
    }

    // --- 取回 ---

    /** 镐子挖掉：盒子里还有东西就先掉出来（方块本体走 loot 表掉空催化剂）。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MobDisplayCatalystBlockEntity catalyst) {
            ItemStack box = catalyst.storedBox();
            if (!box.isEmpty()) {
                Block.popResource(level, pos, box.copy());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 潜行扳手：同时掉空催化剂和盒子（与"挖掉"等价，但一步到位）。 */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ItemStack box = ItemStack.EMPTY;
        if (level.getBlockEntity(pos) instanceof MobDisplayCatalystBlockEntity catalyst) {
            box = catalyst.storedBox().copy();
            catalyst.clearStoredBox();
        }
        level.removeBlock(pos, false);
        Item empty = item(MobDisplayCatalystBlocks.EMPTY_FAN_CATALYST_ID);
        if (empty != null) {
            Block.popResource(level, pos, new ItemStack(empty));
        }
        if (!box.isEmpty()) {
            Block.popResource(level, pos, box);
        }
        return InteractionResult.SUCCESS;
    }

    // --- 小工具 ---

    /** 按 id 取物品（前置模组缺席时返回 null，绝不让注册表查询抛）。 */
    @Nullable
    static Item item(String id) {
        if (!MobDisplayCatalystBlocks.isAvailable()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(key);
    }

    /** 按 id 取方块。 */
    @Nullable
    static Block block(String id) {
        if (!MobDisplayCatalystBlocks.isAvailable()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(key);
    }

    /** 方块/物品"是不是这个 id"的廉价判定（不用建 ItemStack/BlockState 对比）。 */
    static boolean isBlock(BlockState state, String id) {
        Block expected = block(id);
        return expected != null && state.is(expected);
    }

    /** 手上的物品是不是某个 id。 */
    static boolean isItem(ItemStack stack, String id) {
        Item expected = item(id);
        return expected != null && !stack.isEmpty() && stack.is(expected);
    }
}
