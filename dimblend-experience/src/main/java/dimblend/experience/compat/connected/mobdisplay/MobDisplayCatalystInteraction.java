package dimblend.experience.compat.connected.mobdisplay;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 空风扇催化剂 ⇄ 生物展示催化剂的就地转换（Audit Default #12）。
 *
 * <ul>
 * <li>手持<b>装着生物</b>的 {@code creatorsword:capture_box} 右键空催化剂：方块换成展示催化剂，
 * 盒子整只存进方块实体，非创造扣 1 个盒子（{@code ITEM_FRAME_ADD_ITEM}）。</li>
 * <li>右键展示催化剂（非潜行，且手里不是扳手）：方块换回空催化剂，盒子还给玩家
 * （背包塞不下就掉在脚边；{@code ITEM_FRAME_REMOVE_ITEM}）。</li>
 * </ul>
 *
 * <p>两端都要 {@code setCanceled(true)} + {@code setCancellationResult(SUCCESS)}：
 * 否则 {@code CaptureBoxItem#use}（"释放生物"）会顺着手也执行一遍，导致生物被放到世界里而不是被展示。</p>
 */
public final class MobDisplayCatalystInteraction {

    /** 由 {@code DimBlend} 构造期调用（manifest 里有调用行）。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(MobDisplayCatalystInteraction::onRightClickBlock);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!MobDisplayCatalystBlocks.isAvailable()) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        Player player = event.getEntity();
        ItemStack held = event.getItemStack();

        if (state.is(MobDisplayCatalystBlocks.MOB_DISPLAY_CATALYST.get())) {
            if (player.isShiftKeyDown() || player.getMainHandItem().is(com.simibubi.create.AllItems.WRENCH.get())
                    || player.getOffhandItem().is(com.simibubi.create.AllItems.WRENCH.get())) {
                return; // 潜行/扳手留给 IWrenchable 自己处理
            }
            cancelBothSides(event);
            if (level.isClientSide) {
                return;
            }
            retrieve(level, pos, player);
            return;
        }

        if (!MobDisplayCatalystBlock.isBlock(state, MobDisplayCatalystBlocks.EMPTY_FAN_CATALYST_ID)) {
            return;
        }
        if (held.isEmpty() || !MobDisplayCatalystBlock.isItem(held, MobDisplayCatalystBlocks.CAPTURE_BOX_ID)
                || !holdsCapturedEntity(held)) {
            return;
        }
        cancelBothSides(event);
        if (level.isClientSide) {
            return;
        }
        store(level, pos, player, held, event.getHand());
    }

    /** 装进生物：换方块、存盒子、扣物品。 */
    private static void store(Level level, BlockPos pos, Player player, ItemStack held, InteractionHand hand) {
        level.setBlockAndUpdate(pos, MobDisplayCatalystBlocks.MOB_DISPLAY_CATALYST.get().defaultBlockState());
        if (level.getBlockEntity(pos) instanceof MobDisplayCatalystBlockEntity catalyst) {
            catalyst.setStoredBox(held.copyWithCount(1));
        }
        if (!player.isCreative()) {
            held.shrink(1);
        }
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
        player.swing(hand, true);
    }

    /** 取出生物：换回空催化剂，盒子回背包（塞不下就掉在原地）。 */
    private static void retrieve(Level level, BlockPos pos, Player player) {
        ItemStack box = ItemStack.EMPTY;
        if (level.getBlockEntity(pos) instanceof MobDisplayCatalystBlockEntity catalyst) {
            box = catalyst.storedBox().copy();
            // 先清空再换方块：onRemove 里"掉盒子"的分支就不会重复掉一次
            catalyst.clearStoredBox();
        }
        Block empty = MobDisplayCatalystBlock.block(MobDisplayCatalystBlocks.EMPTY_FAN_CATALYST_ID);
        if (empty != null) {
            level.setBlockAndUpdate(pos, empty.defaultBlockState());
        } else {
            level.removeBlock(pos, false);
        }
        if (!box.isEmpty() && !player.addItem(box)) {
            Block.popResource(level, pos, box);
        }
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    /** 盒子里是否真的抓了东西（{@code HasCapturedEntity}）。 */
    private static boolean holdsCapturedEntity(ItemStack box) {
        var data = box.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean("HasCapturedEntity");
    }

    private static void cancelBothSides(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private MobDisplayCatalystInteraction() {
    }
}
