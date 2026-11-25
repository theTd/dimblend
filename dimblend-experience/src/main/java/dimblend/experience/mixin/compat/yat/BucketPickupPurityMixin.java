package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.WaterItemPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 缺口 1：空铁桶舀世界水时把该水体的纯度打到水桶上。
 *
 * <p>{@code BucketItem#use} 的舀水分支是 {@code bucketpickup.pickupBlock(...)}，产物是
 * 全新的 {@code minecraft:water_bucket}（无组件）。这里包住那次调用：先按方块位置取
 * {@code WaterPurity.getBlockPurity}（已由 {@code WaterPurityBlockPosMixin} 做 Sable 投影），
 * 再把它写到返回的水桶上。必须<b>在调用后</b>写（调用前水源方块还在，但返回值才是载体）。</p>
 *
 * <p>只对水生效：岩浆桶/细雪桶 {@code isWaterFilledContainer} 为假，不会被打标。</p>
 */
@Mixin(BucketItem.class)
public abstract class BucketPickupPurityMixin {

    @WrapOperation(method = "use",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"),
            require = 1, allow = 1)
    private static ItemStack dimblend$stampScoopedWater(BucketPickup pickup, Player player, LevelAccessor levelAccessor,
            BlockPos pos, BlockState state, Operation<ItemStack> original) {
        if (!DirtyWaterCompat.purityAvailable() || !(levelAccessor instanceof Level level)) {
            return original.call(pickup, player, levelAccessor, pos, state);
        }
        int purity = WaterPurity.getBlockPurity(level, pos);
        ItemStack filled = original.call(pickup, player, levelAccessor, pos, state);
        if (filled != null && !filled.isEmpty() && filled.is(net.minecraft.world.item.Items.WATER_BUCKET)) {
            WaterItemPurity.write(filled, WaterSourcePurityRules.clampPurity(purity));
        }
        return filled;
    }
}
