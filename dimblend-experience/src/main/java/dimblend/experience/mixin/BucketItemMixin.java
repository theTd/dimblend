package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.exploration.WaterWriteContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * G3 玩家归因（桶倒水）：NeoForge 补丁后玩家手持与发射器倒水统一收敛到
 * {@code BucketItem.emptyContents(Player, Level, BlockPos, BlockHitResult, ItemStack)}
 * 5 参版（4 参委托 5 参，源码核实）。玩家路径 player 非 null，进入前归因、
 * finally 清除；发射器 player=null 不置位——无归因写入按拍板口径仍被 G3 降级。
 *
 * <p>方法内的递归重试（点中面不可替换时换相邻格）是 5 参自重载
 * （{@code emptyContents(player, level, pos, null, container)}，源码核实）：
 * 内层重入本包裹，finally 清除归因后外层立即 return、不再有任何世界写入，
 * 外层 finally 重复清除幂等无害。</p>
 *
 * <p>{@code @WrapMethod} + try/finally：目标中途抛异常时归因也能清除，
 * 不会把玩家残留给主线程后续无关写入。只读玩家引用不读 Config（客户端倒水
 * 预测路径同样经过本方法），双端安全。</p>
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    @WrapMethod(method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z")
    private boolean dimblend$emptyContentsWithAttribution(Player player, Level level, BlockPos pos,
            BlockHitResult hitResult, ItemStack container, Operation<Boolean> original) {
        if (player == null) {
            return original.call(null, level, pos, hitResult, container);
        }
        WaterWriteContext.enterPlayer(player);
        try {
            return original.call(player, level, pos, hitResult, container);
        } finally {
            WaterWriteContext.exitPlayer();
        }
    }
}
