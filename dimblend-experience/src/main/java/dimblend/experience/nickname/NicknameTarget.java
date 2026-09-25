package dimblend.experience.nickname;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 昵称作用目标：主手物品优先；主手为空时取注视方块对应的物品形态。
 * 客户端对话框预览与服务端落库共用这一套，避免两边射线规则漂移。
 */
public final class NicknameTarget {

    public static final int RAY_PICK_RANGE = 8;

    public enum Status {
        FOUND,
        NO_ITEM_FORM,
        NO_TARGET
    }

    /**
     * {@link Status#FOUND} 时 {@code item} 与 {@code itemId} 非空；其余状态两者皆空。
     */
    public record Result(Status status, @Nullable Item item, @Nullable ResourceLocation itemId) {

        public static Result found(Item item) {
            return new Result(Status.FOUND, item, BuiltInRegistries.ITEM.getKey(item));
        }
    }

    public static final Result NO_ITEM_FORM = new Result(Status.NO_ITEM_FORM, null, null);
    public static final Result NO_TARGET = new Result(Status.NO_TARGET, null, null);

    public static Result resolve(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            return Result.found(mainHand.getItem());
        }
        HitResult hit = player.pick(RAY_PICK_RANGE, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockState state = player.level().getBlockState(blockHit.getBlockPos());
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) {
                return NO_ITEM_FORM;
            }
            return Result.found(item);
        }
        return NO_TARGET;
    }

    private NicknameTarget() {
    }
}
